#!/usr/bin/env bash
#
# Startet den Bot lokal zum Entwickeln.
#
# Postgres auf dem Homeserver lauscht nur auf 127.0.0.1 - das bleibt so. Wir
# bauen stattdessen einen SSH-Tunnel auf den lokalen Port 15432. An der
# Server-Konfiguration aendert das nichts, nach aussen bleibt der Port zu.
#
# Token kommt aus .token (nicht im Repo), DB-Zugang aus der env-Datei auf dem
# Homeserver. Beides wird nie ausgegeben.

set -euo pipefail
trap 'echo "ABBRUCH in Zeile ${LINENO} (exit ${?})" >&2' ERR

HOMESERVER="jonas@192.168.178.67"
SSH_KEY="${HOME}/.ssh/fettflix_deploy"
LOCAL_PORT=15432

cd "$(dirname "$0")"

if [ ! -f .token ]; then
    echo "Es fehlt die Datei .token mit dem Bot-Token." >&2
    exit 1
fi

# Tunnel nur aufbauen, wenn der Port noch frei ist.
if ! (exec 3<>/dev/tcp/127.0.0.1/${LOCAL_PORT}) 2>/dev/null; then
    echo "==> SSH-Tunnel auf 127.0.0.1:${LOCAL_PORT} aufbauen"
    ssh -f -N -o ExitOnForwardFailure=yes -i "${SSH_KEY}" \
        -L ${LOCAL_PORT}:127.0.0.1:5432 "${HOMESERVER}"
else
    echo "==> Tunnel steht bereits"
fi

# Zugangsdaten vom Homeserver holen, ohne sie auf die Konsole zu bringen.
DB_ENV=$(ssh -o BatchMode=yes -i "${SSH_KEY}" "${HOMESERVER}" 'cat ~/.config/lostticketbot/db.env')
export TICKETBOT_DB_USER=$(printf '%s\n' "${DB_ENV}" | sed -n 's/^TICKETBOT_DB_USER=//p')
export TICKETBOT_DB_PASSWORD=$(printf '%s\n' "${DB_ENV}" | sed -n 's/^TICKETBOT_DB_PASSWORD=//p')
export TICKETBOT_DB_URL="jdbc:postgresql://127.0.0.1:${LOCAL_PORT}/lostticketbot"

export TICKETBOT_TOKEN=$(tr -d '\r\n' < .token)
export TICKETBOT_STORAGE_GUILD_ID="${TICKETBOT_STORAGE_GUILD_ID:-}"
export TICKETBOT_STORAGE_CHANNEL_ID="${TICKETBOT_STORAGE_CHANNEL_ID:-}"

echo "==> Bot starten"
# Ohne die Encoding-Schalter zerlegt die Windows-Konsole jeden Umlaut.
exec java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8      -jar target/ticketbot.jar
