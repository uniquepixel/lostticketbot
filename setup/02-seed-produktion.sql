-- ENTWURF der Produktivkonfiguration. NICHT ungeprüft ausführen.
--
-- Zusammengetragen aus der Discord-API (Kategorien, Rollen, Panel-Texte,
-- Zählerstände aus 5306 Log-Einträgen) und der Dashboard-Recherche vom
-- 08.09.2026. Alles mit "TODO" markierte konnte ich nicht ermitteln und
-- braucht eine Entscheidung oder einen Blick ins Ticket-Tool-Dashboard.
--
-- Die Zählerstände sind die höchsten im Log gefundenen Nummern. Sie sind so
-- gesetzt, dass das nächste Ticket genau dort weiterzählt, wo Ticket Tool
-- aufgehört hat — die Bewerber sehen also keinen Bruch in der Nummerierung.
--
-- Ausführen:
--   psql -h localhost -U ticketbot -d lostticketbot -v ON_ERROR_STOP=1 -f 02-seed-produktion.sql

BEGIN;

-- ===========================================================================
-- LOST Bewerbungen (1108449987827876022)
-- ===========================================================================

-- Serverweites Limit 1: eine Person darf hier genau ein offenes Ticket haben.
-- Aus dem Dashboard bestätigt; das ist strenger als jedes Panel-Limit und
-- damit die eigentlich wirksame Grenze.
INSERT INTO guild_config (guild_id, global_max_open_tickets)
VALUES ('1108449987827876022', 1)
ON CONFLICT (guild_id) DO UPDATE SET global_max_open_tickets = 1;

-- --- Panels ----------------------------------------------------------------
--
-- Zielkategorien: die Zuordnung Rathausstufe -> Clan ist NICHT aus dem Namen
-- ableitbar, sie stammt aus dem Dashboard. Auffällig: Rh14 zeigt auf LOST 8,
-- und dieser Clan ist geschlossen. Siehe TODO weiter unten.
--
-- Namensmuster: der Bestand ist uneinheitlich gewachsen und enthält echte
-- Tippfehler ("TH 15-{count}" mit Leerzeichen, aus dem Discord "th-15-…"
-- macht). Hier steht die BEREINIGTE Form. Wer die alte Schreibweise erhalten
-- will, muss die Muster unten anpassen — siehe TODO.

INSERT INTO panels (guild_id, name, category_opened, name_pattern_open, name_pattern_closed,
                    counter, counter_padding, welcome_embed_title, welcome_text, welcome_embed_text,
                    log_channel_id, max_open_per_user, close_on_owner_leave, owner_leave_message,
                    active)
VALUES
  -- Clash Royale. Embed-Titel weicht im Bestand bewusst vom Panel-Namen ab.
  ('1108449987827876022', 'CR-lost', '1429181226094166217',
   'lost-cr-{count}', 'lost-cr-closed-{count}', 1317, 4,
   'LOST - Clash Royale Clan',
   'Hey {user}, danke für dein Interesse. Bitte stell dich kurz vor.',
   'Wir melden uns schnellstmöglich.',
   '1312906571944038400', 1, FALSE,
   'Euer Bewerber hat gerade den Server verlassen🏃', TRUE),

  -- Brawl Stars
  ('1108449987827876022', 'BS-lost', '1483210357416923236',
   'lost-bs-{count}', 'lost-bs-closed-{count}', 263, 4,
   'LOST - Brawl Stars Club',
   'Hey {user}, danke für dein Interesse. Bitte stell dich kurz vor.',
   'Wir melden uns schnellstmöglich.',
   '1312906571944038400', 1, FALSE, NULL, TRUE),

  -- Goldpass
  ('1108449987827876022', 'Bewerbung für LOST GP', '1108502565215289394',
   'lost-gp-{count}', 'lost-gp-closed-{count}', 1623, 4,
   'Bewerbung für LOST GP',
   'Hey {user}, danke für deine Bewerbung. Bitte stell dich kurz vor und sende uns einen Screenshot von deinem Dorf, Profil und Equipment.',
   'Wir melden uns schnellstmöglich.',
   '1312906571944038400', 1, FALSE, NULL, TRUE),

  -- F2P. Padding 1 laut Dashboard, also ohne führende Nullen.
  ('1108449987827876022', 'Bewerbung für LOST F2P / LOST F2P 2', '1108472315244720139',
   'lost-f2p-2-{count}', 'lost-f2p-2-closed-{count}', 249, 1,
   'Bewerbung für LOST F2P / LOST F2P 2',
   'Hey {user}, danke für deine Bewerbung. Bitte stell dich kurz vor und sende uns einen Screenshot von deinem Dorf, Profil und Equipment.',
   'Wir melden uns schnellstmöglich.',
   '1312906571944038400', 1, TRUE,
   'Euer Bewerber hat gerade den Server verlassen🏃', TRUE),

  -- Rathausstufen. Jede ein eigenes Panel mit eigenem Zähler.
  ('1108449987827876022', 'Rh18', '1108472347134009504',
   'th18-{count}', 'th18-closed-{count}', 152, 4, 'Rathaus 18',
   'Hey {user}, vielen Dank, dass du dich für einen unserer LOST-Clans bewerben möchtest. Bitte stelle dich kurz vor und sende uns einen Screenshot von deinem Dorf, Profil und Equipment. Wir melden uns schnellstmöglich',
   'Rathaus 18', '1312906571944038400', 1, TRUE,
   'Euer Bewerber hat gerade den Server verlassen🏃', TRUE),

  ('1108449987827876022', 'Rh17', '1108472389148364822',
   'th17-{count}', 'th17-closed-{count}', 454, 4, 'Rathaus 17',
   'Hey {user}, vielen Dank, dass du dich für einen unserer LOST-Clans bewerben möchtest. Bitte stelle dich kurz vor und sende uns einen Screenshot von deinem Dorf, Profil und Equipment. Wir melden uns schnellstmöglich',
   'Rathaus 17', '1312906571944038400', 1, TRUE,
   'Euer Bewerber hat gerade den Server verlassen🏃', TRUE),

  ('1108449987827876022', 'Rh16', '1108472418118406239',
   'th16-{count}', 'th16-closed-{count}', 850, 4, 'Rathaus 16',
   'Hey {user}, vielen Dank, dass du dich für einen unserer LOST-Clans bewerben möchtest. Bitte stelle dich kurz vor und sende uns einen Screenshot von deinem Dorf, Profil und Equipment. Wir melden uns schnellstmöglich',
   'Rathaus 16', '1312906571944038400', 1, TRUE,
   'Euer Bewerber hat gerade den Server verlassen🏃', TRUE),

  ('1108449987827876022', 'Rh15', '1142364732901294140',
   'th15-{count}', 'th15-closed-{count}', 622, 4, 'Rathaus 15',
   'Hey {user}, vielen Dank, dass du dich für einen unserer LOST-Clans bewerben möchtest. Bitte stelle dich kurz vor und sende uns einen Screenshot von deinem Dorf, Profil und Equipment. Wir melden uns schnellstmöglich',
   'Rathaus 15', '1312906571944038400', 1, TRUE,
   'Euer Bewerber hat gerade den Server verlassen🏃', TRUE),

  -- Rh14 und Rh13 sind laut Jonas seit rund zwei Jahren praktisch ungenutzt.
  -- Deshalb angelegt, aber NICHT aktiv und in keinem Menü — so bleiben ihre
  -- 1265 Alt-Tickets zugeordnet, ohne dass sich noch jemand darüber bewirbt.
  ('1108449987827876022', 'Rh14', '1376642172022948011',
   'th14-{count}', 'th14-closed-{count}', 803, 4, 'Rathaus 14',
   'Hey {user}, vielen Dank für deine Bewerbung.', 'Rathaus 14',
   '1312906571944038400', 1, TRUE, NULL, FALSE),

  ('1108449987827876022', 'Rh13', '1332799886118227998',
   'th13-{count}', 'th13-closed-{count}', 462, 4, 'Rathaus 13',
   'Hey {user}, vielen Dank für deine Bewerbung.', 'Rathaus 13',
   '1312906571944038400', 1, TRUE, NULL, FALSE)
ON CONFLICT (guild_id, name) DO NOTHING;

-- Support-Rollen: im Bestand ein GEMEINSAMER Pool über alle Panels, nicht pro
-- Clan getrennt. Ein Vize CR sieht also auch CoC-Bewerbungen. Übernommen wie
-- vorgefunden — siehe TODO, ob das so gewollt ist.
INSERT INTO panel_roles (panel_id, role_id, kind)
SELECT p.id, r.role_id, 'support'
FROM panels p
CROSS JOIN (VALUES
  ('1108482356442058762'),  -- Vize LOST F2P
  ('1108483211459317890'),  -- Vize LOST 3
  ('1108483437662306375'),  -- Vize LOST 4
  ('1108483569044684810'),  -- Vize LOST 5
  ('1108483712137568447'),  -- Vize LOST 6
  ('1147104422334312508'),  -- Vize LOST 7
  ('1108505248877777038'),  -- Vize LOST GP
  ('1332772339640959047'),  -- Vize Anthrazit
  ('1404574565350506587'),  -- Vize CR
  ('1483210115342663752'),  -- Vize BS
  ('1242578112865112236')   -- Orga
) AS r(role_id)
WHERE p.guild_id = '1108449987827876022'
ON CONFLICT DO NOTHING;

-- ===========================================================================
-- LOST Family (733857906117574717)
-- ===========================================================================

-- Limit 100 laut Dashboard, also faktisch unbegrenzt.
INSERT INTO guild_config (guild_id, global_max_open_tickets)
VALUES ('733857906117574717', 100)
ON CONFLICT (guild_id) DO UPDATE SET global_max_open_tickets = 100;

-- Orga-Ticket. Beim Schließen fällt im Bestand der Präfix weg
-- (orga-ticket-0814 -> closed-0815) — hier bewusst nachgebildet.
INSERT INTO panels (guild_id, name, category_opened, name_pattern_open, name_pattern_closed,
                    counter, counter_padding, welcome_embed_text, welcome_text,
                    log_channel_id, max_open_per_user, active)
VALUES
  ('733857906117574717', 'Orga Ticket', '1145329446862192761',
   'orga-ticket-{count}', 'closed-{count}', 815, 4,
   'Bitte teile uns dein Anliegen mit. Wir kümmern uns so schnell wie möglich darum!',
   '{user} Welcome', '1176473916487761920', 1, TRUE),

  -- Aufsteigen: acht Panels, alle in dieselbe Kategorie, eigener Zähler je Clan.
  ('733857906117574717', 'Lost 3', '1284616233962176582',
   'lost-3-{count}', 'lost-3-closed-{count}', 67, 4, 'Lost 3',
   'Hey {user}, schön dass du aufsteigen möchtest.', '1284603641805803560', 1, TRUE),
  ('733857906117574717', 'Lost 4', '1284616233962176582',
   'lost-4-{count}', 'lost-4-closed-{count}', 89, 4, 'Lost 4',
   'Hey {user}, schön dass du aufsteigen möchtest.', '1284603641805803560', 1, TRUE),
  ('733857906117574717', 'Lost 5', '1284616233962176582',
   'lost-5-{count}', 'lost-5-closed-{count}', 88, 4, 'Lost 5',
   'Hey {user}, schön dass du aufsteigen möchtest.', '1284603641805803560', 1, TRUE),
  ('733857906117574717', 'Lost 6', '1284616233962176582',
   'lost-6-{count}', 'lost-6-closed-{count}', 51, 4, 'Lost 6',
   'Hey {user}, schön dass du aufsteigen möchtest.', '1284603641805803560', 1, TRUE),
  ('733857906117574717', 'Lost 7', '1284616233962176582',
   'lost-7-{count}', 'lost-7-closed-{count}', 21, 4, 'Lost 7',
   'Hey {user}, schön dass du aufsteigen möchtest.', '1284603641805803560', 1, TRUE),
  ('733857906117574717', 'Lost GP', '1284616233962176582',
   'lost-gp-{count}', 'lost-gp-closed-{count}', 15, 4, 'Lost GP',
   'Hey {user}, schön dass du aufsteigen möchtest.', '1284603641805803560', 1, TRUE),
  ('733857906117574717', 'Lost F2P', '1284616233962176582',
   'lost-f2p-{count}', 'lost-f2p-closed-{count}', 33, 4, 'Lost F2P',
   'Hey {user}, schön dass du aufsteigen möchtest.', '1284603641805803560', 1, TRUE)
  -- "Lost 8" fehlt hier bewusst: der Clan ist geschlossen. Im Bestand steht er
  -- noch im Dropdown, laut Jonas versehentlich.
ON CONFLICT (guild_id, name) DO NOTHING;

INSERT INTO panel_roles (panel_id, role_id, kind)
SELECT p.id, r.role_id, 'support'
FROM panels p
CROSS JOIN (VALUES
  ('1086732949501788240'),  -- Vize-Anführer F2P
  ('980413464243736626'),   -- Vize-Anführer 3
  ('1412901677048270858'),  -- Vize-Anführer 4
  ('1032936074923745301'),  -- Vize-Anführer 5
  ('1100823609531969607'),  -- Vize-Anführer 6
  ('1144547775455961168'),  -- Vize-Anführer 7
  ('1030199489266458805'),  -- Vize-Anführer GP
  ('734008964496359514')    -- Orga
) AS r(role_id)
WHERE p.guild_id = '733857906117574717'
ON CONFLICT DO NOTHING;

COMMIT;

-- ===========================================================================
-- TODO — Entscheidungen und offene Punkte
-- ===========================================================================
--
-- 1. RH14 ZEIGT AUF EINEN GESCHLOSSENEN CLAN. Im Dashboard führt Rathaus 14
--    in die Kategorie "LOST 8". Der Clan ist zu. Oben ist Rh14 deshalb
--    inaktiv gesetzt; falls die Stufe doch angeboten werden soll, braucht sie
--    eine andere Zielkategorie.
--
-- 2. NAMENSMUSTER BEREINIGT. Oben steht durchgehend "th17-{count}". Der
--    Bestand hat bei Rh13-Rh15 Leerzeichen im Muster, aus denen Discord
--    "th-15-…" macht. Bereinigen heisst: neue Tickets heissen minimal anders
--    als die alten. Wenn das stört, die Muster hier auf die alte Schreibweise
--    zurücksetzen.
--
-- 3. ZIELKATEGORIEN FÜR GP UND BS UNBESTÄTIGT. Ich habe "LOST GP" und
--    "LOST BS" gesetzt, weil sie namentlich passen — im Dashboard nachgesehen
--    wurde nur CR ("CR Tickets") und F2P ("LOST F2P 2").
--
-- 4. GEMEINSAMER ROLLEN-POOL. Alle Panels bekommen dieselben elf Rollen, so
--    wie im Bestand. Damit sieht ein Vize CR auch CoC-Bewerbungen. Falls das
--    gewachsen und nicht gewollt ist, hier pro Panel trennen.
--
-- 5. WILLKOMMENSTEXTE TEILWEISE REKONSTRUIERT. Wörtlich belegt sind nur die
--    Texte von Rathaus 17 und dem Orga-Ticket. Der Rest ist sinngemäss
--    ergänzt und sollte vor dem Umschalten gegengelesen werden.
--
-- 6. KATEGORIE-ÜBERLAUF. "ANGENOMMEN 1" bis "ANGENOMMEN 5" sind im Bestand
--    Ablagen für angenommene Bewerber, keine Zielkategorien beim Öffnen. Der
--    Bot weicht bei vollen Kategorien automatisch auf gleichnamige mit
--    laufender Nummer aus; ob das hier greifen soll, ist zu klären.
--
-- 7. MENÜS FEHLEN NOCH. Dieses Skript legt nur Panels an. Die Menüs entstehen
--    über /menu erstellen, /menu text, /menu hinzufuegen, /menu posten —
--    bewusst über Befehle, damit die Nachrichten dort landen, wo sie sollen.
