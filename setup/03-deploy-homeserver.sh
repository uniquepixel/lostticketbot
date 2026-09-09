#!/usr/bin/env bash
#
# Bringt den Bot als Dienst auf den Homeserver.
#
# Vorher lief er als Vordergrundprozess auf einem Laptop — mit einem
# SSH-Tunnel zur Datenbank und Umgebungsvariablen, die nirgends aufgeschrieben
# waren. Zugeklappter Deckel hiess: kein Ticketsystem auf beiden Discords, und
# niemand ausser dem Startenden wusste, wie man es wieder hochbekommt.
#
# Auf dem Homeserver liegt die Datenbank ohnehin. Der Tunnel entfaellt damit,
# und systemd startet den Dienst nach einem Neustart von selbst wieder.
#
# Mehrfach ausfuehrbar: baut, laedt hoch, prueft die Pruefsumme, tauscht das
# Jar per mv und startet neu. Vorhandene Zugangsdaten bleiben unangetastet.

set -euo pipefail
trap 'echo "ABBRUCH in Zeile ${LINENO} (exit ${?})" >&2' ERR

HOMESERVER="jonas@192.168.178.67"
SSH_KEY="${HOME}/.ssh/fettflix_deploy"
ZIEL="lostticketbot"
DIENST="lostticketbot.service"

cd "$(dirname "$0")/.."

sshx() { ssh -o BatchMode=yes -i "${SSH_KEY}" "${HOMESERVER}" "$@"; }

# ---------------------------------------------------------------------------
# 1. Bauen
# ---------------------------------------------------------------------------
echo "==> Bauen"
./mvnw -q package -DskipTests
test -f target/ticketbot.jar || { echo "target/ticketbot.jar fehlt" >&2; exit 1; }
LOKAL=$(sha256sum target/ticketbot.jar | cut -d' ' -f1)

# ---------------------------------------------------------------------------
# 2. Zugangsdaten
#
# Die Datenbankdaten liegen schon dort (aus 01-postgres-homeserver.sh). Token
# und Kanal-IDs kommen von hier dazu — einmalig, danach bleibt die Datei, wie
# sie ist. Nichts davon wird je ausgegeben.
# ---------------------------------------------------------------------------
echo "==> Zugangsdaten pruefen"
if ! sshx "test -f ~/.config/lostticketbot/bot.env"; then
    echo "    bot.env anlegen"
    test -f .token || { echo ".token fehlt — ohne Bot-Token kein Dienst" >&2; exit 1; }
    TOKEN=$(tr -d '\r\n' < .token)
    API_TOKEN=$(openssl rand -hex 24)
    # Erst holen, dann schreiben: eine Kommandoersetzung mit ssh mitten im
    # Here-Dokument wuerde sich mit dem schreibenden ssh um stdin streiten.
    DB_ENV=$(sshx 'cat ~/.config/lostticketbot/db.env')

    sshx "umask 077; cat > ~/.config/lostticketbot/bot.env" <<ENV
# Vom Deployskript angelegt. Enthaelt Geheimnisse — Rechte 0600, nicht ins Repo.
${DB_ENV}
TICKETBOT_DB_URL=jdbc:postgresql://127.0.0.1:5432/lostticketbot
TICKETBOT_TOKEN=${TOKEN}

# Ablage fuer Anhaenge und Archiv-Posts. MUSS auf LOST Family liegen:
# Boost-Stufe 3 erlaubt dort 100 MB je Datei, der Bewerbungsserver nur 10 MB.
TICKETBOT_STORAGE_GUILD_ID=733857906117574717
TICKETBOT_STORAGE_CHANNEL_ID=1547314036646084628

# Dashboard-API. Lauscht nur auf 127.0.0.1 — nach aussen erreichbar wird sie
# erst durch einen bewussten Tunnel oder Reverse Proxy.
TICKETBOT_API_PORT=7099
TICKETBOT_API_TOKEN=${API_TOKEN}
ENV
    sshx "chmod 600 ~/.config/lostticketbot/bot.env"
    echo "    angelegt. Das API-Token steht dort unter TICKETBOT_API_TOKEN."
else
    echo "    bot.env existiert, bleibt unveraendert"
fi

# ---------------------------------------------------------------------------
# 3. Jar hochladen
#
# Erst neben das laufende Jar, Pruefsumme vergleichen, dann per mv tauschen.
# Niemals cp: die laufende JVM laedt Klassen erst bei Bedarf aus dem Jar, ein
# Ueberschreiben desselben Inode zerlegt sie im Betrieb. Ein Rename laesst
# ihren alten Inode bestehen.
# ---------------------------------------------------------------------------
echo "==> Hochladen"
sshx "mkdir -p ~/${ZIEL} && rm -f ~/${ZIEL}/ticketbot.jar.new"
scp -q -i "${SSH_KEY}" target/ticketbot.jar "${HOMESERVER}:~/${ZIEL}/ticketbot.jar.new"

FERN=$(sshx "sha256sum ~/${ZIEL}/ticketbot.jar.new | cut -d' ' -f1")
if [ "${LOKAL}" != "${FERN}" ]; then
    echo "Pruefsumme stimmt nicht — Upload verworfen." >&2
    sshx "rm -f ~/${ZIEL}/ticketbot.jar.new"
    exit 1
fi
echo "    Pruefsumme stimmt (${LOKAL:0:12}…)"

# ---------------------------------------------------------------------------
# 4. Dienst einrichten
# ---------------------------------------------------------------------------
echo "==> Dienst einrichten"
sshx "mkdir -p ~/.config/systemd/user && cat > ~/.config/systemd/user/${DIENST}" <<'UNIT'
[Unit]
Description=LOST Ticket-Bot
After=network-online.target postgresql.service
Wants=network-online.target

[Service]
Type=simple
WorkingDirectory=%h/lostticketbot
EnvironmentFile=%h/.config/lostticketbot/bot.env
# Ohne die Encoding-Schalter zerlegt jede Umgebung ohne UTF-8-Locale die Umlaute
# in Kanalnamen und Willkommenstexten.
ExecStart=/usr/bin/java -Xmx512m -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 \
    -Dstderr.encoding=UTF-8 -jar %h/lostticketbot/ticketbot.jar
Restart=always
RestartSec=10

[Install]
WantedBy=default.target
UNIT

# ---------------------------------------------------------------------------
# 5. Tauschen und starten
# ---------------------------------------------------------------------------
echo "==> Umschalten"
sshx "export XDG_RUNTIME_DIR=/run/user/\$(id -u)
      systemctl --user daemon-reload
      if systemctl --user is-active --quiet ${DIENST}; then
          cp -f ~/${ZIEL}/ticketbot.jar ~/${ZIEL}/ticketbot.jar.bak-prev 2>/dev/null || true
          systemctl --user stop ${DIENST}
      fi
      mv ~/${ZIEL}/ticketbot.jar.new ~/${ZIEL}/ticketbot.jar
      systemctl --user enable --now ${DIENST}"

sleep 8
echo "==> Zustand"
sshx "export XDG_RUNTIME_DIR=/run/user/\$(id -u)
      systemctl --user is-active ${DIENST}
      journalctl --user -u ${DIENST} -n 15 --no-pager | sed 's/^/    /'"
