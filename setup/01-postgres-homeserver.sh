#!/usr/bin/env bash
#
# Richtet Postgres fuer den LOST Ticket-Bot auf dem Homeserver ein.
#
#   sudo bash 01-postgres-homeserver.sh
#
# Idempotent: mehrfaches Ausfuehren aendert nichts und legt nichts doppelt an.
# Das Passwort wird hier erzeugt und landet ausschliesslich in einer Datei, die
# nur der Benutzer jonas lesen kann - es wird nicht auf der Konsole ausgegeben.

set -euo pipefail

# Ohne das bricht ein Fehler lautlos ab - genau das ist beim ersten Versuch
# passiert (SIGPIPE aus einer head-Pipe, Exit 141, keine Meldung).
trap 'echo "ABBRUCH in Zeile ${LINENO} (exit ${?})" >&2' ERR

DB_NAME="lostticketbot"
DB_USER="ticketbot"
ENV_FILE="/home/jonas/.config/lostticketbot/db.env"
OWNER="jonas"

if [ "$(id -u)" -ne 0 ]; then
    echo "Bitte mit sudo ausfuehren." >&2
    exit 1
fi

# In ein Verzeichnis wechseln, das JEDER lesen darf. "sudo -u postgres" erbt das
# Arbeitsverzeichnis, und /home/jonas ist drwxr-x--- - postgres kaeme dort nicht
# hinein und sudo braeche mit Fehler ab.
cd /

echo "==> Postgres installieren (falls noch nicht vorhanden)"
if ! command -v psql >/dev/null 2>&1; then
    apt-get update -qq
    apt-get install -y postgresql postgresql-contrib
else
    echo "    psql ist bereits da, ueberspringe die Installation."
fi

systemctl enable --now postgresql

echo "==> Rolle und Datenbank anlegen"
# "|| true", damit eine leere Antwort unter "set -e" nicht das Skript beendet.
ROLE_EXISTS=$(sudo -u postgres psql -tAc "SELECT 1 FROM pg_roles WHERE rolname='${DB_USER}'" || true)
if [ "$ROLE_EXISTS" = "1" ]; then
    echo "    Rolle '${DB_USER}' existiert schon - Passwort bleibt unveraendert."
    NEW_PASSWORD=""
else
    # Alphanumerisch, damit JDBC-URL und systemd-Unit nicht ueber Sonderzeichen
    # stolpern. Bewusst OHNE "head" in einer Pipe: head schliesst die Pipe nach
    # n Bytes, der Erzeuger bekommt SIGPIPE, und unter "pipefail" gilt das als
    # Fehler - das hat hier schon zweimal das ganze Skript beendet.
    NEW_PASSWORD=$(openssl rand -hex 24)
    sudo -u postgres psql -qc "CREATE ROLE ${DB_USER} LOGIN PASSWORD '${NEW_PASSWORD}';"
    echo "    Rolle '${DB_USER}' angelegt."
fi

DB_EXISTS=$(sudo -u postgres psql -tAc "SELECT 1 FROM pg_database WHERE datname='${DB_NAME}'" || true)
if [ "$DB_EXISTS" = "1" ]; then
    echo "    Datenbank '${DB_NAME}' existiert schon."
else
    sudo -u postgres createdb -O "${DB_USER}" "${DB_NAME}"
    echo "    Datenbank '${DB_NAME}' angelegt."
fi

if [ -n "$NEW_PASSWORD" ]; then
    echo "==> Zugangsdaten ablegen"
    install -d -o "${OWNER}" -g "${OWNER}" -m 700 "$(dirname "${ENV_FILE}")"
    cat > "${ENV_FILE}" <<INNER
TICKETBOT_DB_URL=jdbc:postgresql://localhost:5432/${DB_NAME}
TICKETBOT_DB_USER=${DB_USER}
TICKETBOT_DB_PASSWORD=${NEW_PASSWORD}
INNER
    chown "${OWNER}:${OWNER}" "${ENV_FILE}"
    chmod 600 "${ENV_FILE}"
    echo "    Geschrieben nach ${ENV_FILE} (nur fuer ${OWNER} lesbar)."
fi

echo
echo "Fertig. Datenbank '${DB_NAME}', Rolle '${DB_USER}', erreichbar auf localhost:5432."
echo "Die Zugangsdaten stehen in ${ENV_FILE}."
echo "Postgres lauscht nur lokal - von aussen ist nichts offen."
