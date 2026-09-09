#!/usr/bin/env bash
#
# Fuehrt die gesamte Testsuite aus, inklusive der Tests gegen echte Datenbank
# und echtes Discord.
#
# Ohne Datenbank oder Token werden die entsprechenden Tests uebersprungen statt
# fehlzuschlagen - die reinen Logiktests laufen immer.
#
# Die Ende-zu-Ende-Tests legen auf dem Testserver Kanaele an und raeumen sie
# wieder weg. Sie gehoeren NICHT auf einen Produktivserver.

set -euo pipefail
trap 'echo "ABBRUCH in Zeile ${LINENO} (exit ${?})" >&2' ERR

HOMESERVER="jonas@192.168.178.67"
SSH_KEY="${HOME}/.ssh/fettflix_deploy"
LOCAL_PORT=15432
TEST_GUILD="${TICKETBOT_TEST_GUILD:-1283191994767642715}"

cd "$(dirname "$0")"

if ! (exec 3<>/dev/tcp/127.0.0.1/${LOCAL_PORT}) 2>/dev/null; then
    echo "==> SSH-Tunnel auf 127.0.0.1:${LOCAL_PORT}"
    ssh -f -N -o ExitOnForwardFailure=yes -i "${SSH_KEY}" \
        -L ${LOCAL_PORT}:127.0.0.1:5432 "${HOMESERVER}"
fi

DB_ENV=$(ssh -o BatchMode=yes -i "${SSH_KEY}" "${HOMESERVER}" 'cat ~/.config/lostticketbot/db.env')
export TICKETBOT_DB_USER=$(printf '%s\n' "${DB_ENV}" | sed -n 's/^TICKETBOT_DB_USER=//p')
export TICKETBOT_DB_PASSWORD=$(printf '%s\n' "${DB_ENV}" | sed -n 's/^TICKETBOT_DB_PASSWORD=//p')
export TICKETBOT_DB_URL="jdbc:postgresql://127.0.0.1:${LOCAL_PORT}/lostticketbot"
export TICKETBOT_TEST_GUILD="${TEST_GUILD}"

if [ -f .token ]; then
    export TICKETBOT_TOKEN=$(tr -d '\r\n' < .token)
else
    echo "==> Keine .token - die Discord-Tests werden uebersprungen"
fi

exec ./mvnw test
