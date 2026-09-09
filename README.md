# LOST Ticket-Bot

Ticketsystem für die LOST-Discordserver. Ersetzt Ticket Tool, läuft selbst gehostet.

Ein Prozess bedient beide Server. Alles Serverabhängige steht in der Datenbank und hängt
an der `guild_id` — es gibt bewusst keine `GUILD_ID`-Umgebungsvariable wie bei den älteren
LOST-Bots.

## Das Datenmodell in einem Absatz

Ein **Panel** ist ein Ticket-Typ mit eigenem Zähler und vollständiger Konfiguration.
Ein **Menü** ist die Nachricht, über die Tickets geöffnet werden, und bietet ein oder
mehrere Panels als Knopfreihe oder Dropdown an.

Diese Aufteilung ist von Ticket Tool übernommen, weil die bestehende Konfiguration so
gewachsen ist: Das Rathaus-Dropdown des Bewerbungsservers besteht aus sechs Panels mit
sechs getrennten Zählerständen, nicht aus einem Panel mit sechs Optionen. Der praktische
Gewinn: Ein Panel aus dem Menü zu nehmen legt es still, ohne seine Alt-Tickets zu
verwaisen — genau der Fall `Rh13`, `Rh14` und `Lost 8`.

## Speicherkonzept

**Discord ist die Wahrheit, die Datenbank ein wegwerfbarer Cache.**

- **Verlauf** wird laufend mitgeschrieben, nicht erst beim Schließen aus der Kanalhistorie
  geholt. Discords Anhang-URLs sind signiert und laufen nach etwa einem Tag ab — wer erst
  beim Schließen greift, hat bei einem zwei Wochen alten Ticket tote Bilder.
- **Anhänge** werden sofort in einen privaten Storage-Kanal gespiegelt. In der Datenbank
  steht nur `channel_id` + `message_id` + `attachment_id`; die signierte URL wird beim
  Anzeigen frisch geholt. Ohne Umkodierung — Speicher ist hier unbegrenzt und kostenlos,
  eine Bildpipeline würde nur ein Problem lösen, das es nicht gibt.
- **Beim Schließen** entsteht ein Archiv-Post im Storage-Kanal: Metadaten im Klartext,
  Volltext als JSON-Anhang. Stirbt die Datenbank, liest ein Durchlauf des Kanals alles
  zurück.

Der Storage-Kanal **muss auf LOST Family liegen** — Boost-Stufe 3 erlaubt dort 100 MB pro
Datei, der Bewerbungsserver ist ungeboostet und kann nur 10 MB.

## Einrichtung

### 1. Datenbank

```bash
sudo bash setup/01-postgres-homeserver.sh
```

Installiert Postgres, legt Rolle und Datenbank an, erzeugt das Passwort selbst und legt es
unter `~/.config/lostticketbot/db.env` mit `0600` ab. Idempotent.

### 2. Discord

Im Developer Portal **beide privilegierten Intents einschalten**:

- `MESSAGE CONTENT` — ohne ihn bleiben alle Transcripts leer
- `SERVER MEMBERS` — ohne ihn merkt der Bot nicht, wenn ein Bewerber den Server verlässt

Einladung mit den nötigen Rechten (Kanäle und Rollen verwalten, Nachrichten senden und
verwalten, Links einbetten, Dateien anhängen, Verlauf lesen):

```
https://discord.com/oauth2/authorize?client_id=<APP_ID>&scope=bot+applications.commands&permissions=268561488
```

### 3. Umgebungsvariablen

Siehe `.env.example`. Nötig sind `TICKETBOT_TOKEN`, die drei `TICKETBOT_DB_*` und
`TICKETBOT_STORAGE_CHANNEL_ID`.

## Entwickeln

```bash
./mvnw package -DskipTests   # baut target/ticketbot.jar
./run-dev.sh                 # SSH-Tunnel zur DB + Bot starten
```

`run-dev.sh` liest den Token aus `.token` (nicht im Repo) und die Datenbankzugangsdaten
über SSH vom Homeserver. Postgres lauscht dort nur auf `127.0.0.1`; der Tunnel ändert daran
nichts, nach außen bleibt der Port zu.

## Befehle

Alle Konfigurationsbefehle setzen `Server verwalten` voraus. `/ticket` wirkt nur im
Ticketkanal selbst.

| Befehl | Zweck |
|---|---|
| `/panel erstellen \| liste \| zeigen \| setzen \| rolle` | Ticket-Typen |
| `/menu erstellen \| text \| hinzufuegen \| entfernen \| posten \| liste` | Menüs |
| `/ticket beanspruchen \| freigeben \| hinzufuegen \| entfernen \| umbenennen \| wiedereroeffnen \| info` | im Ticket |
| `/altdaten importieren \| stand` | Übernahme aus Ticket Tool |

Ein Panel wird angelegt, mit `/panel setzen` konfiguriert, per `/menu hinzufuegen` in ein
Menü gehängt und mit `/menu posten` sichtbar gemacht. Menüs werden bei jedem Start mit der
Konfiguration abgeglichen — eine Änderung wirkt also spätestens nach einem Neustart von
selbst, und ein versehentlich gelöschtes Menü kommt zurück.

## Übernahme der Alt-Transcripts

`/altdaten importieren kanal:#transcripts` liest einen Ticket-Tool-Log-Kanal durch und legt
für jedes Transcript einen Verweis an.

Die HTML-Dateien werden **nicht** neu hochgeladen. Sie liegen bereits als Anhänge in den
Log-Kanälen und damit ohnehin dauerhaft auf Discord; sie herunterzuladen und wieder
hochzuladen wären rund 15 GB Verkehr ohne jeden Gewinn.

Bestand zum Zeitpunkt der Umstellung, gemessen: **5306 Transcripts in drei Log-Kanälen**
(`#transcripts` auf dem Bewerbungsserver mit 4322, `#📃┋transcript` mit 667 und
`#📃┋transcript-log` mit 318 auf LOST Family). Der Befehl ist mehrfach ausführbar, bereits
bekannte Nachrichten werden übersprungen.

## Wissenswertes zu Discord, das hier eingebaut ist

- **50 Kanäle pro Kategorie.** Ist die Zielkategorie voll, sucht der Bot eine gleichnamige
  mit laufender Nummer (`ANGENOMMEN 1` … `ANGENOMMEN 5`). Findet sich keine, wird der Kanal
  ohne Kategorie angelegt statt das Ticket scheitern zu lassen — ein falsch einsortiertes
  Ticket ist ein Ärgernis, ein nicht existierendes ein verlorener Bewerber.
- **Kanalnamen** werden auf Discords Regeln normalisiert, bevor sie in die Datenbank
  gehen. Ticket Tool tut das nicht, weshalb dort Muster wie `TH 15-{count}` stehen, aus
  denen Discord stillschweigend `th-15-…` macht.
- **Interaktionen** müssen binnen drei Sekunden quittiert werden. Alles Langsame läuft
  deshalb nach `deferReply`/`deferEdit` in einem eigenen Thread — und zwar erst im
  Callback, sonst ist es ein Rennen gegen die Quittung und endet in `10062 Unknown
  interaction`.
