#!/usr/bin/env python3
"""Rücklese-Probe des Storage-Kanals: stimmt „Discord ist die Wahrheit“ wirklich?

Liest nur. Geht den Storage-Kanal per REST einmal ganz durch und prüft gegen
die Datenbank:

  1. Jeder Zeiger in transcript_archives zeigt auf einen Post, den es gibt,
     dessen JSON sich lesen lässt und der zum selben Ticket gehört.
  2. Das JSON enthält alle Nachrichten, die die Datenbank bis zum Archiv-
     Zeitpunkt kennt, mit demselben Text.
  3. Jeder gespiegelte Anhang (ticket_attachments) liegt noch im Kanal.
  4. Geschlossene/gelöschte Tickets ohne Archiv, Archiv-Posts ohne Ticket,
     Doppel-Posts (nur gezählt).

Gibt keine Nachrichtentexte und keine Zugangswerte aus, nur Zahlen, Ticket-IDs
und Kanalnamen. Exit 0 = alles deckt sich, 1 = Abweichungen, 2 = Probe selbst
gescheitert.

Aufruf (auf dem Homeserver):
    set -a; . ~/.config/lostticketbot/bot.env; set +a
    python3 archiv-probe.py [--ausfuehrlich] [--max-seiten N]
"""
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request

API = "https://discord.com/api/v10"
AGENT = "DiscordBot (https://github.com/discord/discord-api-docs, 1.0) archiv-probe"
KOPF = re.compile(r"Ticket-ID (\d+)\s*$")
# Zwischen INSERT der letzten Nachricht und archived_at liegt der Upload.
# Nachrichten, die in dieser Spanne eintreffen, dürfen im Archiv fehlen.
EPOCH_DISCORD = 1420070400000


def umgebung(name):
    wert = os.environ.get(name, "").strip()
    if not wert:
        print(f"Probe nicht möglich: {name} ist nicht gesetzt.", file=sys.stderr)
        sys.exit(2)
    return wert


def hole(url, token=None, versuche=5):
    """GET mit Rücksicht auf Discords Drosselung. Liefert Bytes."""
    for versuch in range(versuche):
        anfrage = urllib.request.Request(url, headers={"User-Agent": AGENT})
        if token:
            anfrage.add_header("Authorization", "Bot " + token)
        try:
            with urllib.request.urlopen(anfrage, timeout=30) as antwort:
                return antwort.read()
        except urllib.error.HTTPError as fehler:
            if fehler.code == 429:
                try:
                    warte = float(json.loads(fehler.read()).get("retry_after", 2))
                except (ValueError, AttributeError):
                    warte = 2.0
                time.sleep(min(warte + 0.2, 30))
                continue
            if fehler.code >= 500 and versuch + 1 < versuche:
                time.sleep(2 + versuch)
                continue
            raise
        except (urllib.error.URLError, TimeoutError):
            if versuch + 1 < versuche:
                time.sleep(2 + versuch)
                continue
            raise
    raise RuntimeError("zu oft gedrosselt: " + url.split("?")[0])


def sql(abfrage):
    """Führt eine SELECT-Abfrage aus und liefert die Zeilen als dicts."""
    url = umgebung("TICKETBOT_DB_URL")
    treffer = re.match(r"jdbc:postgresql://([^:/]+)(?::(\d+))?/([^?]+)", url)
    if not treffer:
        print("Probe nicht möglich: TICKETBOT_DB_URL hat ein unbekanntes Format.", file=sys.stderr)
        sys.exit(2)
    host, port, db = treffer.group(1), treffer.group(2) or "5432", treffer.group(3)
    umg = dict(os.environ, PGPASSWORD=umgebung("TICKETBOT_DB_PASSWORD"),
               PGOPTIONS="-c default_transaction_read_only=on")
    lauf = subprocess.run(
        ["psql", "-h", host, "-p", port, "-U", umgebung("TICKETBOT_DB_USER"), "-d", db,
         "-X", "-A", "-t", "-v", "ON_ERROR_STOP=1",
         "-c", f"SELECT COALESCE(json_agg(z), '[]') FROM ({abfrage}) z"],
        env=umg, capture_output=True, text=True)
    if lauf.returncode != 0:
        print("Datenbankabfrage gescheitert: " + lauf.stderr.strip()[:300], file=sys.stderr)
        sys.exit(2)
    return json.loads(lauf.stdout)


def kanal_durchgehen(kanal, token, max_seiten):
    """Alle Nachrichten des Kanals, neueste zuerst. Nur was die Probe braucht."""
    nachrichten = {}
    vor = None
    seiten = 0
    while True:
        url = f"{API}/channels/{kanal}/messages?limit=100" + (f"&before={vor}" if vor else "")
        seite = json.loads(hole(url, token))
        seiten += 1
        for n in seite:
            nachrichten[n["id"]] = {
                "bot": bool(n.get("author", {}).get("bot")),
                "kopf": n.get("content") or "",
                "anhaenge": [{"id": a["id"], "name": a.get("filename", ""), "url": a.get("url", ""),
                              "groesse": a.get("size", 0)} for a in n.get("attachments", [])],
            }
        if len(seite) < 100 or (max_seiten and seiten >= max_seiten):
            return nachrichten, len(seite) < 100
        vor = seite[-1]["id"]


def main():
    ausfuehrlich = "--ausfuehrlich" in sys.argv
    max_seiten = 0
    if "--max-seiten" in sys.argv:
        max_seiten = int(sys.argv[sys.argv.index("--max-seiten") + 1])

    token = umgebung("TICKETBOT_TOKEN")
    kanal = umgebung("TICKETBOT_STORAGE_CHANNEL_ID")

    tickets = {t["id"]: t for t in sql(
        "SELECT t.id, t.guild_id, t.channel_name, t.status, t.owner_id, "
        "(SELECT COUNT(*) FROM ticket_messages m WHERE m.ticket_id = t.id) AS nachrichten, "
        "(SELECT COUNT(*) FROM ticket_messages m WHERE m.ticket_id = t.id AND NOT m.author_bot) AS menschen "
        "FROM tickets t")}
    zeiger = sql("SELECT ticket_id, storage_channel_id, storage_message_id, "
                 "(EXTRACT(EPOCH FROM archived_at) * 1000)::bigint AS archiviert_ms FROM transcript_archives")
    db_nachrichten = {}
    for z in sql("SELECT ticket_id, message_id, COALESCE(content, '') AS content, "
                 "(EXTRACT(EPOCH FROM sent_at) * 1000)::bigint AS gesendet_ms, "
                 "(EXTRACT(EPOCH FROM edited_at) * 1000)::bigint AS bearbeitet_ms "
                 "FROM ticket_messages WHERE ticket_id IN (SELECT ticket_id FROM transcript_archives)"):
        db_nachrichten.setdefault(z["ticket_id"], {})[z["message_id"]] = z
    anhaenge = sql("SELECT ticket_id, filename, storage_channel_id, storage_message_id, storage_attach_id "
                   "FROM ticket_attachments")

    start = time.time()
    posts, vollstaendig = kanal_durchgehen(kanal, token, max_seiten)
    print(f"Storage-Kanal: {len(posts)} Nachrichten gelesen in {time.time() - start:.0f} s"
          + ("" if vollstaendig else "  (ABGEBROCHEN nach --max-seiten, Ergebnis unvollständig)"))

    fehler = []       # echte Abweichungen
    hinweise = []     # erklärbar, nur zur Einordnung

    # --- Archiv-Posts im Kanal ---------------------------------------------
    archivposts = {}  # ticket_id -> [message_id]
    for mid, p in posts.items():
        treffer = KOPF.search(p["kopf"])
        if treffer and any(a["name"].endswith(".json") for a in p["anhaenge"]):
            archivposts.setdefault(int(treffer.group(1)), []).append(mid)
    doppel = sum(len(v) - 1 for v in archivposts.values() if len(v) > 1)
    fremd = sorted(t for t in archivposts if t not in tickets)

    # --- 1 und 2: Zeiger und Inhalt ------------------------------------------
    geprueft = fehlende_nachrichten = abweichende_texte = 0
    for z in sorted(zeiger, key=lambda z: z["ticket_id"]):
        tid = z["ticket_id"]
        name = tickets.get(tid, {}).get("channel_name", "?")
        if z["storage_channel_id"] != kanal:
            hinweise.append(f"Ticket {tid} ({name}): Archiv liegt in anderem Kanal {z['storage_channel_id']}, nicht geprüft")
            continue
        post = posts.get(z["storage_message_id"])
        if post is None:
            if vollstaendig:
                fehler.append(f"Ticket {tid} ({name}): Archiv-Post {z['storage_message_id']} gibt es nicht mehr")
            continue
        datei = next((a for a in post["anhaenge"] if a["name"].endswith(".json")), None)
        if datei is None:
            fehler.append(f"Ticket {tid} ({name}): Archiv-Post ohne JSON-Anhang")
            continue
        try:
            archiv = json.loads(hole(datei["url"]))
        except (ValueError, urllib.error.URLError, RuntimeError) as e:
            fehler.append(f"Ticket {tid} ({name}): JSON nicht lesbar ({type(e).__name__})")
            continue
        geprueft += 1
        if z["storage_message_id"] not in archivposts.get(tid, []):
            # Ohne Datenbank fände ein Durchlauf diesen Post nur über das JSON.
            hinweise.append(f"Ticket {tid} ({name}): Kopfzeile des Archiv-Posts nennt die Ticket-ID nicht "
                            f"(Länge {len(post['kopf'])}, {len(post['anhaenge'])} Anhänge)")
        if archiv.get("ticket_id") != tid:
            fehler.append(f"Ticket {tid} ({name}): JSON gehört zu Ticket {archiv.get('ticket_id')}")
            continue
        im_archiv = {m.get("message_id"): m for m in archiv.get("messages", [])}
        fehlt = anders = spaeter = 0
        for mid, m in db_nachrichten.get(tid, {}).items():
            if mid not in im_archiv:
                # Nach dem Archiv geschrieben? Dann fehlt sie zu Recht (bis zum Löschen).
                if m["gesendet_ms"] > z["archiviert_ms"] - 5000:
                    spaeter += 1
                else:
                    fehlt += 1
                continue
            bearbeitet = m["bearbeitet_ms"]
            if (im_archiv[mid].get("content") or "") != m["content"] and not (
                    bearbeitet and bearbeitet > z["archiviert_ms"] - 5000):
                anders += 1
        nur_archiv = sum(1 for mid in im_archiv if mid not in db_nachrichten.get(tid, {}))
        fehlende_nachrichten += fehlt
        abweichende_texte += anders
        if fehlt or anders or nur_archiv:
            fehler.append(f"Ticket {tid} ({name}): {fehlt} Nachrichten fehlen im Archiv, "
                          f"{anders} mit anderem Text, {nur_archiv} nur im Archiv")
        if spaeter and tickets.get(tid, {}).get("status") == "deleted":
            fehler.append(f"Ticket {tid} ({name}): gelöscht, aber {spaeter} Nachrichten sind jünger als das Archiv")
        elif spaeter and ausfuehrlich:
            hinweise.append(f"Ticket {tid} ({name}): {spaeter} Nachrichten nach dem Archiv (Kanal steht noch)")

    # --- 3: gespiegelte Anhänge --------------------------------------------
    anhang_fehlt = 0
    anhang_anderswo = 0
    for a in anhaenge:
        if a["storage_channel_id"] != kanal:
            anhang_anderswo += 1
            continue
        post = posts.get(a["storage_message_id"])
        if post is None or not any(x["id"] == a["storage_attach_id"] for x in post["anhaenge"]):
            if vollstaendig or post is not None:
                anhang_fehlt += 1
                name = tickets.get(a["ticket_id"], {}).get("channel_name", "?")
                fehler.append(f"Ticket {a['ticket_id']} ({name}): gespiegelter Anhang "
                              f"{a['storage_message_id']} liegt nicht mehr im Storage-Kanal")

    # --- 4: Tickets ohne Archiv -----------------------------------------------
    mit_zeiger = {z["ticket_id"] for z in zeiger}
    ohne = [t for t in tickets.values() if t["status"] in ("closed", "deleted") and t["id"] not in mit_zeiger]
    ohne_mit_inhalt = [t for t in ohne if t["menschen"] > 0]
    for t in ohne_mit_inhalt:
        zusatz = " – im Kanal liegt aber ein Archiv-Post" if t["id"] in archivposts else ""
        fehler.append(f"Ticket {t['id']} ({t['channel_name']}): {t['status']}, "
                      f"{t['menschen']} Nachrichten von Menschen, kein Archiv-Zeiger{zusatz}")

    print(f"Archive: {len(zeiger)} Zeiger, {geprueft} JSON gelesen und verglichen, "
          f"{fehlende_nachrichten} fehlende Nachrichten, {abweichende_texte} abweichende Texte")
    print(f"Anhänge: {len(anhaenge)} gespiegelt, {anhang_fehlt} nicht mehr auffindbar"
          + (f", {anhang_anderswo} in anderem Kanal (nicht geprüft)" if anhang_anderswo else ""))
    print(f"Ohne Archiv: {len(ohne)} geschlossene/gelöschte Tickets, davon {len(ohne_mit_inhalt)} mit Nachrichten von Menschen")
    print(f"Im Kanal: {sum(len(v) for v in archivposts.values())} Archiv-Posts zu {len(archivposts)} Tickets, "
          f"{doppel} Doppel, {len(fremd)} zu Tickets, die die Datenbank nicht kennt")
    if fremd and ausfuehrlich:
        hinweise.append("Archiv-Posts ohne Ticket in der Datenbank (Testläufe?): "
                        + ", ".join(str(t) for t in fremd[:40]) + (" …" if len(fremd) > 40 else ""))

    for h in hinweise:
        print("  Hinweis: " + h)
    if fehler:
        print(f"ABWEICHUNGEN ({len(fehler)}):")
        for f in fehler:
            print("  " + f)
        return 1
    print("Deckt sich: was die Datenbank kennt, liegt im Storage-Kanal.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
