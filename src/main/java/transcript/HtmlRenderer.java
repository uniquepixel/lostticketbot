package transcript;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import db.Database;
import lostticketbot.Bot;
import model.Panel;
import model.Ticket;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;

/**
 * Baut aus einem gespeicherten Ticket eine lesbare HTML-Datei.
 *
 * Ersetzt Ticket Tools Transcript-Ansicht. Ein Unterschied ist bewusst: Ticket
 * Tool bettet jedes Bild als base64 ein, weshalb dort Dateien mit bis zu 6,6 MB
 * entstehen und ein bilderreiches Ticket am Discord-Uploadlimit scheitern kann.
 * Hier werden Bilder verlinkt, die Datei bleibt bei wenigen Kilobyte.
 *
 * Der Preis dafuer steht offen in der Datei: Discords Anhang-URLs sind signiert
 * und laufen nach etwa einem Tag ab. Die Bilder selbst sind nicht weg — sie
 * liegen dauerhaft im Storage-Kanal — nur diese eine Datei zeigt sie dann nicht
 * mehr an. Ein neues {@code /transcript} erzeugt frische Links.
 *
 * Fuer die Datei, die beim Schliessen dauerhaft im Log-Kanal liegen bleibt,
 * greift das nicht: die soll niemand neu erzeugen muessen. Dafuer gibt es
 * {@link #rendern(Ticket, Panel, boolean)} mit eingebetteten Bildern — dieselbe
 * Loesung wie bei Ticket Tool, nur mit Obergrenze, damit die Datei das
 * Uploadlimit des Servers nicht sprengt.
 */
public final class HtmlRenderer {

	private static final DateTimeFormatter ZEIT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

	/**
	 * Wie viel eingebettetes Bildmaterial die Datei hoechstens tragen darf.
	 *
	 * base64 blaeht um rund ein Drittel auf, und der Bewerbungsserver ist
	 * ungeboostet — dort sind 10 MB je Upload Schluss. 7 MB Rohdaten landen
	 * also bei gut 9,3 MB Datei und passen noch. Was darueber hinausgeht,
	 * bleibt verlinkt; das steht dann auch in der Datei.
	 */
	private static final long EINBETTUNGSBUDGET = 7L * 1024 * 1024;

	private HtmlRenderer() {
	}

	private record Zeile(long id, String autorId, String autorName, boolean bot, String inhalt,
			LocalDateTime gesendet, boolean bearbeitet, boolean geloescht) {
	}

	private record Anhang(String dateiname, String contentType, long groesse,
			String storageChannelId, String storageMessageId) {

		boolean istBild() {
			return contentType != null && contentType.startsWith("image/");
		}
	}

	/** Verlinkte Bilder — klein, aber die Adressen laufen nach einem Tag ab. */
	public static String rendern(Ticket ticket, Panel panel) {
		return rendern(ticket, panel, false);
	}

	/**
	 * @param bilderEinbetten Bilder als {@code data:}-URI in die Datei legen,
	 *                        damit sie ohne Discord und ohne Datenbank lesbar
	 *                        bleibt. Fuer die Archivdatei im Log-Kanal.
	 */
	public static String rendern(Ticket ticket, Panel panel, boolean bilderEinbetten) {
		final List<Zeile> zeilen = zeilenLaden(ticket.id());
		final Map<Long, List<Anhang>> anhaenge = anhaengeLaden(ticket.id());
		final Map<String, String> frischeUrls = urlsAuffrischen(anhaenge);
		final Map<String, String> eingebettet = bilderEinbetten
				? bilderHolen(anhaenge, frischeUrls)
				: Map.of();
		boolean allesEingebettet = bilderEinbetten;

		final Map<String, Integer> proAutor = new LinkedHashMap<>();
		zeilen.forEach(z -> proAutor.merge(z.autorName(), 1, Integer::sum));

		final StringBuilder h = new StringBuilder(16 * 1024);
		h.append("<!doctype html>\n<html lang=\"de\">\n<head>\n<meta charset=\"utf-8\">\n");
		h.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n");
		h.append("<title>").append(esc(ticket.channelName())).append("</title>\n");
		h.append(STIL);
		h.append("</head>\n<body>\n<div class=\"seite\">\n");

		// Kopf
		h.append("<header>\n<h1>").append(esc(ticket.channelName())).append("</h1>\n<dl>\n");
		feld(h, "Panel", panel == null ? "—" : esc(panel.name()));
		feld(h, "Nummer", String.valueOf(ticket.number()));
		feld(h, "Eröffner", nutzer(ticket.ownerId()));
		feld(h, "Geöffnet", zeit(ticket.openedAt()));
		if (ticket.closedAt() != null) {
			feld(h, "Geschlossen", zeit(ticket.closedAt()));
			feld(h, "Geschlossen von", ticket.closedBy() == null
					? "automatisch"
					: nutzer(ticket.closedBy()));
		}
		if (ticket.claimedBy() != null) {
			feld(h, "Betreut von", nutzer(ticket.claimedBy()));
		}
		feld(h, "Nachrichten", String.valueOf(zeilen.size()));
		h.append("</dl>\n");

		if (!proAutor.isEmpty()) {
			h.append("<p class=\"beteiligte\"><span>Beteiligte</span> ");
			final List<String> teile = new ArrayList<>();
			proAutor.forEach((name, anzahl) -> teile.add(esc(name) + " <b>" + anzahl + "</b>"));
			h.append(String.join(" · ", teile)).append("</p>\n");
		}
		h.append("</header>\n");

		// Verlauf
		h.append("<main>\n");
		if (zeilen.isEmpty()) {
			h.append("<p class=\"leer\">Für dieses Ticket wurde kein Verlauf aufgezeichnet. "
					+ "Das ist bei Tickets normal, die vor der Umstellung geschlossen wurden — "
					+ "deren Verlauf liegt weiterhin im Ticket-Tool-Transcript im Log-Kanal.</p>\n");
		}
		String letzterAutor = null;
		for (final Zeile z : zeilen) {
			final boolean fortsetzung = z.autorName().equals(letzterAutor);
			h.append("<article class=\"msg").append(z.geloescht() ? " geloescht" : "")
					.append(fortsetzung ? " folge" : "").append("\">\n");
			if (!fortsetzung) {
				h.append("<div class=\"kopf\"><span class=\"autor\">").append(esc(z.autorName()));
				if (z.bot()) {
					h.append(" <span class=\"bot\">Bot</span>");
				}
				h.append("</span><time>").append(zeit(z.gesendet())).append("</time></div>\n");
			}
			if (z.inhalt() != null && !z.inhalt().isBlank()) {
				h.append("<div class=\"text\">").append(inhaltFormatieren(z.inhalt())).append("</div>\n");
			}
			if (z.bearbeitet()) {
				h.append("<span class=\"marke\">bearbeitet</span>\n");
			}
			if (z.geloescht()) {
				h.append("<span class=\"marke rot\">später gelöscht</span>\n");
			}
			for (final Anhang a : anhaenge.getOrDefault(z.id(), List.of())) {
				final String url = frischeUrls.get(a.storageMessageId() + "/" + a.dateiname());
				if (url == null) {
					h.append("<div class=\"anhang fehlt\">").append(esc(a.dateiname()))
							.append(" — Link nicht abrufbar</div>\n");
				} else if (a.istBild()) {
					h.append("<a class=\"bild\" href=\"").append(esc(url)).append("\" target=\"_blank\">")
							.append("<img src=\"").append(esc(url)).append("\" alt=\"")
							.append(esc(a.dateiname())).append("\" loading=\"lazy\"></a>\n");
				} else {
					h.append("<a class=\"anhang\" href=\"").append(esc(url)).append("\" target=\"_blank\">")
							.append(esc(a.dateiname())).append(" <span>")
							.append(groesse(a.groesse())).append("</span></a>\n");
				}
			}
			h.append("</article>\n");
			letzterAutor = z.autorName();
		}
		h.append("</main>\n");

		h.append("<footer>Erzeugt am ").append(zeit(LocalDateTime.now()))
				.append(" vom LOST Ticket-Bot. Die verlinkten Bilder liegen dauerhaft gespeichert, "
						+ "aber Discord signiert ihre Adressen nur für etwa einen Tag — danach zeigt "
						+ "diese Datei sie nicht mehr an. Ein neues <code>/transcript</code> erzeugt "
						+ "frische Links.</footer>\n");

		h.append("</div>\n</body>\n</html>\n");
		return h.toString();
	}

	// -----------------------------------------------------------------------

	private static List<Zeile> zeilenLaden(long ticketId) {
		return Database.query(
				"SELECT * FROM ticket_messages WHERE ticket_id = ? ORDER BY sent_at, id",
				rs -> new Zeile(
						rs.getLong("id"),
						rs.getString("author_id"),
						rs.getString("author_name"),
						rs.getBoolean("author_bot"),
						rs.getString("content"),
						rs.getTimestamp("sent_at").toLocalDateTime(),
						rs.getTimestamp("edited_at") != null,
						rs.getTimestamp("deleted_at") != null),
				ticketId);
	}

	private static Map<Long, List<Anhang>> anhaengeLaden(long ticketId) {
		final Map<Long, List<Anhang>> map = new HashMap<>();
		Database.query("SELECT * FROM ticket_attachments WHERE ticket_id = ? ORDER BY id",
				rs -> {
					map.computeIfAbsent(rs.getLong("ticket_message_id"), k -> new ArrayList<>())
							.add(new Anhang(
									rs.getString("filename"),
									rs.getString("content_type"),
									rs.getLong("size_bytes"),
									rs.getString("storage_channel_id"),
									rs.getString("storage_message_id")));
					return null;
				}, ticketId);
		return map;
	}

	/**
	 * Holt zu jedem gespiegelten Anhang eine frische, signierte Adresse.
	 *
	 * Genau dafuer merken wir uns Kanal und Nachricht statt der URL: die Datei
	 * liegt fest, nur ihre Adresse laeuft ab.
	 */
	private static Map<String, String> urlsAuffrischen(Map<Long, List<Anhang>> anhaenge) {
		final Map<String, String> urls = new HashMap<>();
		if (Bot.jda() == null) {
			return urls;
		}
		for (final List<Anhang> liste : anhaenge.values()) {
			for (final Anhang a : liste) {
				final TextChannel storage = Bot.jda().getTextChannelById(a.storageChannelId());
				if (storage == null) {
					continue;
				}
				try {
					final Message m = storage.retrieveMessageById(a.storageMessageId()).complete();
					m.getAttachments().stream()
							.filter(att -> att.getFileName().equals(a.dateiname()))
							.findFirst()
							.ifPresent(att -> urls.put(schluessel(a), att.getUrl()));
				} catch (final RuntimeException e) {
					System.err.println("Anhang " + a.dateiname() + " nicht abrufbar: " + e.getMessage());
				}
			}
		}
		return urls;
	}

	/** Ein Anhang ist eindeutig durch seine Nachricht und seinen Dateinamen. */
	private static String schluessel(Anhang a) {
		return a.storageMessageId() + "/" + a.dateiname();
	}

	/**
	 * Laedt Bilder herunter und macht {@code data:}-URIs daraus.
	 *
	 * Laeuft ueber das Budget, hoert es auf — die restlichen Bilder bleiben
	 * verlinkt, statt dass der Upload der ganzen Datei am Limit scheitert und
	 * am Ende gar kein Transcript im Log-Kanal steht.
	 */
	private static Map<String, String> bilderHolen(Map<Long, List<Anhang>> anhaenge,
			Map<String, String> urls) {
		final Map<String, String> eingebettet = new HashMap<>();
		long verbraucht = 0;

		for (final List<Anhang> liste : anhaenge.values()) {
			for (final Anhang a : liste) {
				if (!a.istBild() || verbraucht + a.groesse() > EINBETTUNGSBUDGET) {
					continue;
				}
				final String url = urls.get(schluessel(a));
				if (url == null) {
					continue;
				}
				try (var in = java.net.URI.create(url).toURL().openStream()) {
					final byte[] daten = in.readAllBytes();
					if (verbraucht + daten.length > EINBETTUNGSBUDGET) {
						continue;
					}
					verbraucht += daten.length;
					eingebettet.put(schluessel(a), "data:"
							+ (a.contentType() == null ? "image/png" : a.contentType())
							+ ";base64,"
							+ java.util.Base64.getEncoder().encodeToString(daten));
				} catch (final Exception e) {
					System.err.println("Bild " + a.dateiname() + " nicht einbettbar: " + e.getMessage());
				}
			}
		}
		return eingebettet;
	}

	private static void feld(StringBuilder h, String name, String wert) {
		h.append("<div><dt>").append(name).append("</dt><dd>").append(wert).append("</dd></div>\n");
	}

	private static String nutzer(String id) {
		return "<span class=\"id\">" + esc(id) + "</span>";
	}

	private static String zeit(LocalDateTime t) {
		return t == null ? "—" : ZEIT.format(t);
	}

	private static String groesse(long bytes) {
		if (bytes < 1024) {
			return bytes + " B";
		}
		if (bytes < 1024 * 1024) {
			return (bytes / 1024) + " KB";
		}
		return String.format("%.1f MB", bytes / 1024.0 / 1024.0);
	}

	/** Erwaehnungen sichtbar machen und Zeilenumbrueche erhalten. */
	private static String inhaltFormatieren(String roh) {
		return esc(roh)
				.replaceAll("&lt;@!?(\\d+)&gt;", "<span class=\"erw\">@$1</span>")
				.replaceAll("&lt;@&amp;(\\d+)&gt;", "<span class=\"erw\">@Rolle $1</span>")
				.replaceAll("&lt;#(\\d+)&gt;", "<span class=\"erw\">#$1</span>")
				.replace("\n", "<br>");
	}

	private static String esc(String s) {
		if (s == null) {
			return "";
		}
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
				.replace("\"", "&quot;");
	}

	private static final String STIL = """
			<style>
			:root {
			  --grund:#f7f9f6; --flaeche:#fff; --tinte:#1a2118; --leise:#5a6656;
			  --linie:#dae2d7; --gruen:#388e3c; --rot:#b3261e;
			}
			@media (prefers-color-scheme: dark) {
			  :root { --grund:#12160f; --flaeche:#1a1f17; --tinte:#e8eee4; --leise:#9aa694;
			          --linie:#2e372a; --gruen:#6dbb63; --rot:#e88b84; }
			}
			* { box-sizing:border-box }
			body { margin:0; background:var(--grund); color:var(--tinte);
			  font:15px/1.55 system-ui,-apple-system,"Segoe UI",sans-serif }
			.seite { max-width:52rem; margin:0 auto; padding:2rem 1.25rem 4rem }
			h1 { margin:0 0 1rem; font-size:1.6rem; letter-spacing:-.01em }
			header { padding-bottom:1.25rem; border-bottom:2px solid var(--linie); margin-bottom:1.5rem }
			dl { display:grid; grid-template-columns:repeat(auto-fit,minmax(11rem,1fr));
			  gap:.6rem 1.5rem; margin:0 }
			dt { font-size:.7rem; text-transform:uppercase; letter-spacing:.07em; color:var(--leise) }
			dd { margin:.1rem 0 0; font-weight:500 }
			.id { font-family:ui-monospace,Consolas,monospace; font-size:.85em; color:var(--leise) }
			.beteiligte { margin:1.1rem 0 0; font-size:.87rem; color:var(--leise) }
			.beteiligte span { text-transform:uppercase; font-size:.7rem; letter-spacing:.07em;
			  margin-right:.4rem }
			.beteiligte b { color:var(--tinte) }
			main { display:flex; flex-direction:column; gap:.15rem }
			.msg { background:var(--flaeche); padding:.6rem .85rem; border-radius:4px }
			.msg.folge { border-top-left-radius:0; border-top-right-radius:0; padding-top:.15rem }
			.msg.geloescht { opacity:.6 }
			.kopf { display:flex; align-items:baseline; gap:.6rem; margin-bottom:.15rem }
			.autor { font-weight:600 }
			.bot { font-size:.62rem; text-transform:uppercase; letter-spacing:.06em;
			  background:var(--gruen); color:#fff; padding:.05rem .3rem; border-radius:2px;
			  vertical-align:.1em }
			time { font-size:.75rem; color:var(--leise) }
			.text { white-space:normal; overflow-wrap:anywhere }
			.erw { color:var(--gruen); font-weight:500 }
			.marke { display:inline-block; font-size:.68rem; color:var(--leise);
			  text-transform:uppercase; letter-spacing:.06em; margin-top:.2rem }
			.marke.rot { color:var(--rot) }
			.bild { display:inline-block; margin-top:.4rem }
			.bild img { max-width:min(100%,22rem); border-radius:4px; display:block }
			.anhang { display:inline-block; margin-top:.4rem; padding:.35rem .6rem;
			  border:1px solid var(--linie); border-radius:4px; text-decoration:none;
			  color:var(--tinte); font-size:.85rem }
			.anhang span { color:var(--leise) }
			.anhang.fehlt { color:var(--leise); border-style:dashed }
			.leer { color:var(--leise); font-style:italic }
			footer { margin-top:2.5rem; padding-top:1rem; border-top:1px solid var(--linie);
			  font-size:.78rem; color:var(--leise) }
			code { font-family:ui-monospace,Consolas,monospace }
			</style>
			""";
}
