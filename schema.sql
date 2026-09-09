-- Schema des LOST Ticket-Bots — Ersatz für Ticket Tool auf beiden LOST-Servern.
--
-- Zwei Grundsätze, die überall durchschlagen:
--
-- 1. Multi-Guild von Anfang an. Ein Prozess bedient "LOST Bewerbungen" und
--    "LOST Family"; nichts wird hartkodiert, alles hängt an guild_id.
--
-- 2. Discord ist die Wahrheit, diese Datenbank ist ein wegwerfbarer Cache.
--    Transcript-Volltexte und Anhänge liegen in einem privaten Storage-Kanal
--    auf LOST Family (Boost-Stufe 3, 100 MB pro Datei). Was hier steht, lässt
--    sich daraus jederzeit neu aufbauen — siehe transcript_archives.
--
-- Das Datenmodell folgt bewusst dem von Ticket Tool, weil die bestehende
-- Konfiguration so gewachsen ist: ein PANEL ist ein Ticket-Typ mit eigenem
-- Zähler und vollständiger Konfiguration; ein MENU ist eine Nachricht, die
-- mehrere Panels als Button oder Dropdown anbietet. Nicht andersherum — die
-- getrennten Zählerstände je Rathausstufe belegen es.

-- ---------------------------------------------------------------------------
-- Server-Ebene
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS guild_config (
    guild_id                TEXT PRIMARY KEY,
    -- Ticket Tool kennt ein serverweites Limit zusätzlich zum Panel-Limit.
    -- Gemessen am 08.09.2026: Bewerbungen = 1, Family = 100. Auf dem
    -- Bewerbungsserver ist das die eigentlich wirksame Grenze, nicht das Panel.
    global_max_open_tickets INTEGER NOT NULL DEFAULT 0,   -- 0 = unbegrenzt
    panel_embed_color       INTEGER NOT NULL DEFAULT 3706428,  -- #388e3c
    ticket_embed_color      INTEGER NOT NULL DEFAULT 2016348,  -- #1ec45c
    created_at              TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON COLUMN guild_config.global_max_open_tickets IS
    'Serverweites Limit offener Tickets pro Nutzer, 0 = unbegrenzt. Wirkt zusätzlich zu panels.max_open_per_user; die kleinere Zahl gewinnt.';

-- ---------------------------------------------------------------------------
-- Panels = Ticket-Typen
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS panels (
    id                   BIGSERIAL PRIMARY KEY,
    guild_id             TEXT    NOT NULL,
    name                 TEXT    NOT NULL,      -- interner Name, z.B. 'Rh17', 'CR-lost'
    active               BOOLEAN NOT NULL DEFAULT TRUE,

    category_opened      TEXT,                  -- Kategorie für neue Tickets
    category_closed      TEXT,                  -- leer = Ticket bleibt liegen wo es ist

    -- Getrennte Muster für offen und geschlossen, weil die Bestandsdaten das
    -- erzwingen: das Orga-Panel macht aus 'orga-ticket-0814' schlicht
    -- 'closed-0815', alle anderen schieben '-closed-' in der Mitte ein.
    -- {count} ist der Zähler, {user} der Nutzername.
    name_pattern_open    TEXT    NOT NULL DEFAULT 'ticket-{count}',
    name_pattern_closed  TEXT    NOT NULL DEFAULT 'ticket-closed-{count}',
    counter              INTEGER NOT NULL DEFAULT 0,
    counter_padding      SMALLINT NOT NULL DEFAULT 4,   -- führende Nullen; Ticket Tool nennt es "Ticket Padding"

    welcome_text         TEXT,                  -- Fließtext über dem Embed, {user} wird ersetzt
    welcome_embed_title  TEXT,                  -- meist der Panel-Name; CR-lost weicht bewusst ab
    welcome_embed_text   TEXT,

    log_channel_id       TEXT,                  -- MUSS am Panel hängen, nicht an der Guild:
                                                -- LOST Family loggt aus zwei Menüs in zwei Kanäle
    max_open_per_user    INTEGER NOT NULL DEFAULT 1,
    cooldown_seconds     INTEGER NOT NULL DEFAULT 0,

    -- Ticket Tool nennt das "Missing user check": verlässt der Bewerber den
    -- Server, wird sein Ticket automatisch geschlossen. Bei den Bestandspanels
    -- uneinheitlich gesetzt (F2P an, CR-lost aus); wir vereinheitlichen das.
    close_on_owner_leave BOOLEAN NOT NULL DEFAULT FALSE,
    owner_leave_message  TEXT,

    dm_on_open           BOOLEAN NOT NULL DEFAULT FALSE,
    dm_on_close          BOOLEAN NOT NULL DEFAULT FALSE,

    created_at           TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (guild_id, name)
);

COMMENT ON TABLE panels IS
    'Ein Panel ist ein Ticket-Typ mit eigenem Zähler. Das Rathaus-Dropdown des Bewerbungsservers besteht aus sechs solcher Panels, nicht aus einem.';
COMMENT ON COLUMN panels.counter_padding IS
    'Stellenzahl mit führenden Nullen. Bestand ist uneinheitlich: Aufsteigen-Panels 4, das F2P-Panel 1 (also gar keine Auffüllung).';

-- Support- und Ping-Rollen als eigene Tabelle statt Array, damit sich einzelne
-- Rollen entfernen lassen ohne das Panel zu schreiben. Im Bestand ist das ein
-- gemeinsamer Pool über alle Panels — das Modell erlaubt aber pro Panel eigene.
CREATE TABLE IF NOT EXISTS panel_roles (
    panel_id  BIGINT NOT NULL REFERENCES panels(id) ON DELETE CASCADE,
    role_id   TEXT   NOT NULL,
    kind      TEXT   NOT NULL,   -- 'support' = Zugriff aufs Ticket, 'ping' = Benachrichtigung beim Öffnen
    PRIMARY KEY (panel_id, role_id, kind)
);

-- ---------------------------------------------------------------------------
-- Menüs = die Nachrichten, über die Tickets geöffnet werden
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS menus (
    id            BIGSERIAL PRIMARY KEY,
    guild_id      TEXT    NOT NULL,
    channel_id    TEXT    NOT NULL,
    message_id    TEXT,                    -- NULL bis das Menü das erste Mal gepostet wurde
    style         TEXT    NOT NULL DEFAULT 'buttons',   -- 'buttons' | 'select'
    embed_title   TEXT,
    embed_text    TEXT,
    embed_color   INTEGER,                 -- NULL = Wert aus guild_config
    placeholder   TEXT,                    -- nur bei style='select'
    created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS menu_entries (
    menu_id     BIGINT   NOT NULL REFERENCES menus(id)  ON DELETE CASCADE,
    panel_id    BIGINT   NOT NULL REFERENCES panels(id) ON DELETE CASCADE,
    label       TEXT     NOT NULL,          -- was der Nutzer sieht, z.B. 'Rathaus 17'
    description TEXT,
    emoji       TEXT,
    button_style SMALLINT NOT NULL DEFAULT 2,
    position    SMALLINT NOT NULL DEFAULT 0,
    PRIMARY KEY (menu_id, panel_id)
);

COMMENT ON TABLE menu_entries IS
    'Verbindet Menü und Panel. Ein Panel aus dem Menü zu nehmen legt es still, ohne es zu löschen — genau der Fall Rh13/Rh14/Lost 8.';

-- ---------------------------------------------------------------------------
-- Tickets
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS tickets (
    id            BIGSERIAL PRIMARY KEY,
    guild_id      TEXT      NOT NULL,
    panel_id      BIGINT    REFERENCES panels(id) ON DELETE SET NULL,
    number        INTEGER   NOT NULL,        -- Zählerstand des Panels bei Erstellung
    channel_id    TEXT      NOT NULL UNIQUE,
    channel_name  TEXT      NOT NULL,
    owner_id      TEXT      NOT NULL,
    claimed_by    TEXT,
    status        TEXT      NOT NULL DEFAULT 'open',   -- open | closed | deleted
    opened_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    closed_at     TIMESTAMP,
    closed_by     TEXT,
    close_reason  TEXT
);

CREATE TABLE IF NOT EXISTS ticket_members (
    ticket_id BIGINT NOT NULL REFERENCES tickets(id) ON DELETE CASCADE,
    user_id   TEXT   NOT NULL,
    added_by  TEXT,
    added_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (ticket_id, user_id)
);

-- ---------------------------------------------------------------------------
-- Transcripts
-- ---------------------------------------------------------------------------

-- Nachrichten werden LAUFEND mitgeschrieben, nicht erst beim Schließen aus der
-- Kanalhistorie geholt. Zwei Gründe: Discord-Anhang-URLs sind signiert und
-- laufen nach rund einem Tag ab — wer erst beim Schließen greift, hat bei einem
-- zwei Wochen alten Ticket tote Bilder. Und gelöschte oder bearbeitete
-- Nachrichten bleiben so nachvollziehbar, was in einem Beschwerde-Ticket eher
-- Feature als Problem ist.
--
-- Volumen ist unkritisch: reiner Text sind ~10 KB je Ticket, für die gesamte
-- Historie beider Server (5307 Tickets) rund 53 MB.
CREATE TABLE IF NOT EXISTS ticket_messages (
    id           BIGSERIAL PRIMARY KEY,
    ticket_id    BIGINT    NOT NULL REFERENCES tickets(id) ON DELETE CASCADE,
    message_id   TEXT      NOT NULL,
    author_id    TEXT      NOT NULL,
    author_name  TEXT      NOT NULL,
    author_bot   BOOLEAN   NOT NULL DEFAULT FALSE,
    content      TEXT,
    embeds_json  JSONB,
    sent_at      TIMESTAMP NOT NULL,
    edited_at    TIMESTAMP,
    deleted_at   TIMESTAMP,
    UNIQUE (ticket_id, message_id)
);

-- Anhänge liegen NICHT hier, sondern als Nachricht im Discord-Storage-Kanal.
-- Gespeichert wird nur der Zeiger; die signierte URL wird beim Anzeigen frisch
-- geholt und ein paar Stunden gecacht.
CREATE TABLE IF NOT EXISTS ticket_attachments (
    id                 BIGSERIAL PRIMARY KEY,
    ticket_id          BIGINT NOT NULL REFERENCES tickets(id) ON DELETE CASCADE,
    ticket_message_id  BIGINT REFERENCES ticket_messages(id) ON DELETE CASCADE,
    filename           TEXT   NOT NULL,
    content_type       TEXT,
    size_bytes         BIGINT,
    storage_channel_id TEXT   NOT NULL,
    storage_message_id TEXT   NOT NULL,
    storage_attach_id  TEXT   NOT NULL,
    stored_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE ticket_attachments IS
    'Zeiger in den Discord-Storage-Kanal. Originaldateien werden unverändert übernommen — kein Transcoding, weil Speicher unbegrenzt und gratis ist und eine Bildpipeline nur ein Problem löst, das es nicht gibt.';

-- Beim Schließen wird ein Archiv-Post im Storage-Kanal angelegt: Metadaten im
-- Nachrichtentext, Volltext als JSON-Anhang. Damit ist die Datenbank
-- rekonstruierbar — stirbt sie, liest ein Durchlauf des Kanals alles zurück.
CREATE TABLE IF NOT EXISTS transcript_archives (
    ticket_id          BIGINT PRIMARY KEY REFERENCES tickets(id) ON DELETE CASCADE,
    storage_channel_id TEXT   NOT NULL,
    storage_message_id TEXT   NOT NULL,
    archived_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Die 5307 Alt-Transcripts aus Ticket Tool werden NICHT neu hochgeladen. Ihre
-- HTML-Dateien liegen bereits als Anhänge in den drei Log-Kanälen; wir merken
-- uns nur, wo. Das spart 14 GB Transfer und ist genauso dauerhaft.
CREATE TABLE IF NOT EXISTS legacy_transcripts (
    id             BIGSERIAL PRIMARY KEY,
    guild_id       TEXT NOT NULL,
    log_channel_id TEXT NOT NULL,
    log_message_id TEXT NOT NULL UNIQUE,
    attachment_id  TEXT NOT NULL,
    filename       TEXT NOT NULL,
    size_bytes     BIGINT,
    ticket_name    TEXT,
    panel_name     TEXT,
    owner_id       TEXT,
    closed_by      TEXT,
    closed_at      TIMESTAMP
);

COMMENT ON TABLE legacy_transcripts IS
    'Index auf die von Ticket Tool erzeugten HTML-Transcripts, die an ihrem Platz in den Log-Kanälen liegen bleiben. Volltext-Suche darüber kann später nachgezogen werden.';

-- ---------------------------------------------------------------------------
-- Blacklist
-- ---------------------------------------------------------------------------

-- Im Bestand wird das Blacklist-Feature von Ticket Tool nicht genutzt (Feld auf
-- beiden Servern leer); die Kanäle #blacklist pflegen die Leader von Hand.
-- Trotzdem vorgesehen, weil es beim Nachbau nichts kostet.
CREATE TABLE IF NOT EXISTS blacklist (
    id         BIGSERIAL PRIMARY KEY,
    guild_id   TEXT NOT NULL,
    subject_id TEXT NOT NULL,        -- User- oder Rollen-ID
    is_role    BOOLEAN NOT NULL DEFAULT FALSE,
    reason     TEXT,
    added_by   TEXT,
    added_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (guild_id, subject_id)
);

-- ---------------------------------------------------------------------------
-- Indizes
-- ---------------------------------------------------------------------------

CREATE INDEX IF NOT EXISTS idx_tickets_owner       ON tickets(guild_id, owner_id, status);
CREATE INDEX IF NOT EXISTS idx_tickets_panel       ON tickets(panel_id, status);
CREATE INDEX IF NOT EXISTS idx_tickets_channel     ON tickets(channel_id);
CREATE INDEX IF NOT EXISTS idx_ticket_messages_tid ON ticket_messages(ticket_id, sent_at);
CREATE INDEX IF NOT EXISTS idx_attachments_ticket  ON ticket_attachments(ticket_id);
CREATE INDEX IF NOT EXISTS idx_panels_guild        ON panels(guild_id, active);
CREATE INDEX IF NOT EXISTS idx_menus_message       ON menus(message_id);
CREATE INDEX IF NOT EXISTS idx_legacy_panel        ON legacy_transcripts(guild_id, panel_name);
CREATE INDEX IF NOT EXISTS idx_legacy_owner        ON legacy_transcripts(owner_id);
