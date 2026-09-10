#!/usr/bin/env bash
#
# Lagebericht zum Ticketsystem. Liest nur, aendert nichts.
#
# Gedacht fuer den taeglichen Blick: ein Aufruf, ein Bild. Aufgeschrieben statt
# jedes Mal neu zusammengesucht — sonst prueft man an verschiedenen Tagen
# verschiedene Dinge und merkt nicht, was fehlt.
#
# Aufruf: ./check.sh

set -uo pipefail

HOMESERVER="jonas@192.168.178.67"
SSH_KEY="${HOME}/.ssh/fettflix_deploy"
DIENST="lostticketbot.service"
TICKET_TOOL="557628352828014614"

cd "$(dirname "$0")"

# Laeuft der Bericht auf dem Homeserver selbst, faellt SSH weg — dort gibt es
# keinen Schluessel auf die eigene Maschine, und er waere auch unsinnig. Erkannt
# wird das an der Zugangsdatei des Bots, die nur dort liegt.
if [ -f "${HOME}/.config/lostticketbot/bot.env" ]; then
    AUF_DEM_SERVER=1
else
    AUF_DEM_SERVER=0
fi

sshx() {
    if [ "${AUF_DEM_SERVER}" = "1" ]; then
        bash -c "$1" 2>/dev/null
    else
        ssh -o BatchMode=yes -o ConnectTimeout=10 -i "${SSH_KEY}" "${HOMESERVER}" "$1" 2>/dev/null
    fi
}

# Fuehrt SQL auf dem Homeserver aus. Zugangsdaten bleiben dort.
sql() {
    sshx "set -a; . ~/.config/lostticketbot/bot.env; set +a
          PGPASSWORD=\"\$TICKETBOT_DB_PASSWORD\" psql -h 127.0.0.1 -U \"\$TICKETBOT_DB_USER\" \
              -d lostticketbot -tAF'|' -c \"$1\""
}

titel() { printf '\n\033[1m%s\033[0m\n' "$1"; }

echo "════════════════════════════════════════════════════════"
echo " LOST Ticket-Bot · Lagebericht $(date '+%d.%m.%Y %H:%M')"
echo "════════════════════════════════════════════════════════"

# ---------------------------------------------------------------------------
# Dienst
# ---------------------------------------------------------------------------
titel "Dienst"
ZUSTAND=$(sshx "export XDG_RUNTIME_DIR=/run/user/\$(id -u)
                systemctl --user show ${DIENST} \
                    -p ActiveState -p NRestarts -p MemoryCurrent -p ExecMainStartTimestamp")
if [ -z "${ZUSTAND}" ]; then
    echo "  NICHT ERREICHBAR — Homeserver antwortet nicht."
else
    STATUS=$(printf '%s\n' "${ZUSTAND}" | sed -n 's/^ActiveState=//p')
    NEUSTARTS=$(printf '%s\n' "${ZUSTAND}" | sed -n 's/^NRestarts=//p')
    SPEICHER=$(printf '%s\n' "${ZUSTAND}" | sed -n 's/^MemoryCurrent=//p')
    SEIT=$(printf '%s\n' "${ZUSTAND}" | sed -n 's/^ExecMainStartTimestamp=//p')
    printf '  Status       %s\n' "${STATUS}"
    printf '  Seit         %s\n' "${SEIT:-unbekannt}"
    printf '  Neustarts    %s%s\n' "${NEUSTARTS}" \
        "$([ "${NEUSTARTS:-0}" -gt 0 ] 2>/dev/null && echo '   <-- nachsehen, warum' || true)"
    if [ -n "${SPEICHER}" ] && [ "${SPEICHER}" != "[not set]" ]; then
        printf '  Speicher     %s MB\n' "$((SPEICHER / 1024 / 1024))"
    fi
fi

# ---------------------------------------------------------------------------
# Fehler der letzten 24 Stunden
# ---------------------------------------------------------------------------
titel "Fehler (24 h)"
FEHLER=$(sshx "export XDG_RUNTIME_DIR=/run/user/\$(id -u)
               journalctl --user -u ${DIENST} --since '24 hours ago' --no-pager 2>/dev/null \
                   | grep -iE 'exception|fehlgeschlagen|konnte nicht|nicht gefunden|ERROR' \
                   | grep -v 'INFO' | tail -12")
if [ -z "${FEHLER}" ]; then
    echo "  keine"
else
    printf '%s\n' "${FEHLER}" | sed 's/^/  /'
fi

# ---------------------------------------------------------------------------
# Dashboard-API
# ---------------------------------------------------------------------------
titel "Dashboard-API"
API=$(sshx "curl -s -m 8 -o /dev/null -w '%{http_code}' http://127.0.0.1:7099/api/health")
if [ "${API}" = "200" ]; then
    echo "  erreichbar (Port 7099)"
else
    echo "  ANTWORTET NICHT (HTTP ${API:-000})"
fi

# ---------------------------------------------------------------------------
# Tickets je Server
# ---------------------------------------------------------------------------
titel "Tickets"
sql "SELECT COALESCE(g.name, t.guild_id), \
            count(*) FILTER (WHERE t.status = 'open'), \
            count(*) FILTER (WHERE t.status = 'open' AND t.claimed_by IS NULL), \
            count(*) FILTER (WHERE t.status = 'closed'), \
            count(*) FILTER (WHERE t.opened_at > now() - interval '24 hours') \
     FROM tickets t \
     LEFT JOIN (VALUES ('733857906117574717','LOST Family'), \
                       ('1108449987827876022','LOST Bewerbungen'), \
                       ('1283191994767642715','Testserver')) AS g(guild_id, name) \
            ON g.guild_id = t.guild_id \
     GROUP BY 1 ORDER BY 2 DESC" \
| while IFS='|' read -r name offen frei zu neu; do
    [ -z "${name}" ] && continue
    printf '  %-20s %3s offen · %3s unbeansprucht · %3s geschlossen · %s neu heute\n' \
        "${name}" "${offen}" "${frei}" "${zu}" "${neu}"
done

# ---------------------------------------------------------------------------
# Liegengebliebenes
#
# Das ist der eigentliche Zweck des taeglichen Blicks: nicht ob der Bot laeuft,
# sondern ob jemand auf eine Antwort wartet.
# ---------------------------------------------------------------------------
titel "Wartet seit über 7 Tagen, niemand zuständig"
LIEGT=$(sql "SELECT t.channel_name, date_part('day', now() - t.opened_at)::int, t.owner_id \
             FROM tickets t \
             WHERE t.status = 'open' AND t.claimed_by IS NULL \
               AND t.opened_at < now() - interval '7 days' \
               AND t.guild_id <> '1283191994767642715' \
             ORDER BY t.opened_at LIMIT 15")
if [ -z "${LIEGT}" ]; then
    echo "  nichts"
else
    printf '%s\n' "${LIEGT}" | while IFS='|' read -r kanal tage owner; do
        printf '  %-32s %4s Tage   <@%s>\n' "${kanal}" "${tage}" "${owner}"
    done
fi

# ---------------------------------------------------------------------------
# Archiv-Rueckstand
#
# Ein geschlossenes Ticket ohne Archiv ist genau der Fall, in dem ein
# geloeschter Kanal den Verlauf mitnimmt.
# ---------------------------------------------------------------------------
titel "Archiv"
# Nur Tickets mit echtem Inhalt zaehlen. Uebernommene Alt-Tickets haben bei uns
# nie Verlauf aufgezeichnet - ihr Inhalt liegt in den Ticket-Tool-Transcripts.
# Die als Rueckstand zu melden, wuerde den Blick auf die echten Faelle verstellen.
OHNE=$(sql "SELECT count(*) FROM tickets t LEFT JOIN transcript_archives a ON a.ticket_id = t.id WHERE t.status IN ('closed','deleted') AND a.ticket_id IS NULL AND t.guild_id <> '1283191994767642715' AND EXISTS (SELECT 1 FROM ticket_messages m WHERE m.ticket_id = t.id AND NOT m.author_bot)")
MIT=$(sql "SELECT count(*) FROM transcript_archives")
ALT=$(sql "SELECT count(*) FROM legacy_transcripts")
printf '  %s archiviert · %s aus Ticket Tool übernommen\n' "${MIT:-?}" "${ALT:-?}"
if [ "${OHNE:-0}" = "0" ]; then
    echo "  kein Rückstand"
else
    echo "  ${OHNE} geschlossene Tickets OHNE Archiv — nachsehen"
fi

# ---------------------------------------------------------------------------
# Karteileichen
#
# Wird ein Ticketkanal von Hand geloescht, erfaehrt der Bot davon nichts: in der
# Datenbank steht er weiter als offen, und im Lagebericht taucht er als
# wartender Bewerber auf, den es nicht mehr gibt. Am 10.09.2026 waren es zwei.
# ---------------------------------------------------------------------------
titel "Kanäle, die es nicht mehr gibt"
TK=""
if [ "${AUF_DEM_SERVER}" = "1" ]; then
    # Auf dem Server steht der Token in der Zugangsdatei, nicht in .token.
    TK=$(sed -n 's/^TICKETBOT_TOKEN=//p' "${HOME}/.config/lostticketbot/bot.env")
elif [ -f .token ]; then
    TK=$(tr -d '\r\n' < .token)
fi

if [ -n "${TK}" ]; then
    GEFUNDEN=0
    LEICHEN=$(sql "SELECT id, channel_id, channel_name, status FROM tickets WHERE status <> 'deleted' AND guild_id <> '1283191994767642715' ORDER BY id")
    while IFS="|" read -r tid kanal name status; do
        [ -z "${kanal}" ] && continue
        CODE=$(curl -s -m 10 -o /dev/null -w '%{http_code}' -H "Authorization: Bot ${TK}" "https://discord.com/api/v10/channels/${kanal}")
        if [ "${CODE}" != "200" ]; then
            printf '  id %-4s %-32s steht noch als "%s"\n' "${tid}" "${name}" "${status}"
            GEFUNDEN=$((GEFUNDEN + 1))
        fi
        sleep 0.2
    done <<< "${LEICHEN}"
    [ "${GEFUNDEN}" = "0" ] && echo "  keine"
else
    echo "  übersprungen (kein Bot-Token gefunden)"
fi

# ---------------------------------------------------------------------------
# Rueckweg und Platz
# ---------------------------------------------------------------------------
titel "Umfeld"
PLATTE=$(sshx "df -h / | tail -1 | awk '{print \$4\" frei von \"\$2}'")
printf '  Platte       %s\n' "${PLATTE:-unbekannt}"

if [ -n "${TK}" ]; then
    for GUILD in 733857906117574717 1108449987827876022; do
        DA=$(curl -s -m 10 -H "Authorization: Bot ${TK}" \
            "https://discord.com/api/v10/guilds/${GUILD}/members/${TICKET_TOOL}" \
            | grep -c '"user"' || true)
        NAME=$([ "${GUILD}" = "733857906117574717" ] && echo "LOST Family" || echo "LOST Bewerbungen")
        if [ "${DA}" = "1" ]; then
            printf '  Ticket Tool  noch auf %s — Rückweg steht\n' "${NAME}"
        else
            printf '  Ticket Tool  NICHT mehr auf %s — kein Rückweg\n' "${NAME}"
        fi
    done
fi

echo
