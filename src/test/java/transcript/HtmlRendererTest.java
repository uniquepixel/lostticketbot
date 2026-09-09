package transcript;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import db.Database;
import db.PanelDao;
import db.TicketDao;
import model.Panel;
import model.Ticket;

/**
 * Erzeugung der Transcript-Datei.
 *
 * Legt eigene Daten an, statt sich auf ein vorhandenes Ticket zu verlassen —
 * damit prueft der Test dieselben Faelle bei jedem Lauf, auch die unangenehmen:
 * geloeschte Nachricht, bearbeitete Nachricht, Anhang ohne abrufbaren Link,
 * und Inhalt, der wie HTML aussieht.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HtmlRendererTest {

	private static final String GUILD = "test-guild-renderer";
	private boolean verfuegbar;
	private long panelId;
	private long ticketId;

	@BeforeAll
	void setUp() {
		final String url = System.getenv("TICKETBOT_DB_URL");
		verfuegbar = url != null && !url.isBlank();
		assumeTrue(verfuegbar, "TICKETBOT_DB_URL nicht gesetzt — uebersprungen");

		Database.init(url, System.getenv("TICKETBOT_DB_USER"),
				System.getenv().getOrDefault("TICKETBOT_DB_PASSWORD", ""));
		Database.applySchema();
		aufraeumen();

		panelId = PanelDao.create(GUILD, "renderer");
		PanelDao.set(panelId, "name_pattern_open", "rt-{count}");
		ticketId = TicketDao.create(GUILD, panelId, 7, "kanal-rt", "rt-0007", "111");

		zeile("m1", "222", "simon", false, "Hallo, ich würde gern beitreten <@333>", null, null);
		zeile("m2", "333", "phillip", false, "Willkommen! Welche Taktik spielst du?", "now()", null);
		zeile("m3", "222", "simon", false, "<script>alert('xss')</script> & ein \"Zitat\"", null, null);
		zeile("m4", "444", "geloeschter", false, "Das war unhöflich", null, "now()");

		// Anhang, dessen Link sich nicht auffrischen laesst (kein JDA im Test).
		Database.update(
				"INSERT INTO ticket_attachments (ticket_id, ticket_message_id, filename, content_type, "
						+ "size_bytes, storage_channel_id, storage_message_id, storage_attach_id) "
						+ "SELECT ?, id, 'dorf.png', 'image/png', 204800, '1', '2', '3' "
						+ "FROM ticket_messages WHERE ticket_id = ? AND message_id = 'm1'",
				ticketId, ticketId);
	}

	private void zeile(String msgId, String autorId, String name, boolean bot, String inhalt,
			String bearbeitet, String geloescht) {
		Database.update(
				"INSERT INTO ticket_messages (ticket_id, message_id, author_id, author_name, "
						+ "author_bot, content, sent_at, edited_at, deleted_at) "
						+ "VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, "
						+ (bearbeitet == null ? "NULL" : "CURRENT_TIMESTAMP") + ", "
						+ (geloescht == null ? "NULL" : "CURRENT_TIMESTAMP") + ")",
				ticketId, msgId, autorId, name, bot, inhalt);
	}

	@AfterAll
	void tearDown() {
		if (verfuegbar) {
			aufraeumen();
			Database.shutdown();
		}
	}

	private void aufraeumen() {
		Database.update("DELETE FROM ticket_attachments WHERE ticket_id IN "
				+ "(SELECT id FROM tickets WHERE guild_id = ?)", GUILD);
		Database.update("DELETE FROM ticket_messages WHERE ticket_id IN "
				+ "(SELECT id FROM tickets WHERE guild_id = ?)", GUILD);
		Database.update("DELETE FROM tickets WHERE guild_id = ?", GUILD);
		Database.update("DELETE FROM panels WHERE guild_id = ?", GUILD);
	}

	private String html() {
		final Ticket t = TicketDao.byId(ticketId).orElseThrow();
		final Panel p = PanelDao.byId(panelId).orElseThrow();
		return HtmlRenderer.rendern(t, p);
	}

	// -----------------------------------------------------------------------

	@Test
	@DisplayName("Grundgerüst und Kopfdaten stehen drin")
	void grundgeruest() {
		final String h = html();
		assertTrue(h.startsWith("<!doctype html>"), "Muss ein vollständiges Dokument sein");
		assertTrue(h.contains("<title>rt-0007</title>"));
		assertTrue(h.contains("renderer"), "Panelname fehlt");
		assertTrue(h.contains("</html>"), "Dokument nicht geschlossen");
		assertTrue(h.contains("prefers-color-scheme: dark"), "Dunkles Theme fehlt");
	}

	@Test
	@DisplayName("Alle Nachrichten erscheinen in der richtigen Reihenfolge")
	void verlauf() {
		final String h = html();
		final int erste = h.indexOf("Hallo, ich würde gern beitreten");
		final int zweite = h.indexOf("Welche Taktik spielst du");
		assertTrue(erste > 0 && zweite > erste, "Reihenfolge stimmt nicht");
		assertTrue(h.contains("simon") && h.contains("phillip"));
	}

	@Test
	@DisplayName("HTML im Nachrichteninhalt wird entschärft, nicht ausgeführt")
	void keinXss() {
		final String h = html();
		assertFalse(h.contains("<script>alert"), "Skript-Tag ist ungefiltert durchgerutscht");
		assertTrue(h.contains("&lt;script&gt;"), "Muss maskiert erscheinen");
		assertTrue(h.contains("&quot;Zitat&quot;") || h.contains("&amp;"), "Sonderzeichen maskiert");
	}

	@Test
	@DisplayName("Erwähnungen werden lesbar statt als Rohform")
	void erwaehnungen() {
		final String h = html();
		assertTrue(h.contains("class=\"erw\">@333"), "Erwähnung nicht umgesetzt");
		assertFalse(h.contains("&lt;@333&gt;"), "Rohform steht noch drin");
	}

	@Test
	@DisplayName("Bearbeitet und gelöscht werden gekennzeichnet, Inhalt bleibt lesbar")
	void markierungen() {
		final String h = html();
		assertTrue(h.contains("bearbeitet"), "Bearbeitungsmarke fehlt");
		assertTrue(h.contains("später gelöscht"), "Löschmarke fehlt");
		// Der Inhalt einer geloeschten Nachricht bleibt stehen - genau dafuer
		// schreiben wir laufend mit.
		assertTrue(h.contains("Das war unhöflich"), "Gelöschter Inhalt darf nicht verschwinden");
	}

	@Test
	@DisplayName("Anhang ohne abrufbaren Link bricht die Datei nicht")
	void anhangOhneLink() {
		final String h = html();
		assertTrue(h.contains("dorf.png"), "Dateiname fehlt");
		assertTrue(h.contains("Link nicht abrufbar"), "Fehlender Link muss benannt werden");
		assertFalse(h.contains("src=\"null\""), "Kaputtes img-Tag");
	}

	@Test
	@DisplayName("Eingebettete Fassung sagt ehrlich, wenn ein Bild nicht hineinpasste")
	void einbettungOhneErreichbaresBild() {
		// Ohne laufendes JDA laesst sich keine frische Adresse holen, also kann
		// auch nichts eingebettet werden. Genau dieser Fall darf die Datei nicht
		// zerreissen und muss im Fusstext stehen — sonst haelt jemand eine
		// unvollstaendige Archivdatei fuer vollstaendig.
		final Ticket t = TicketDao.byId(ticketId).orElseThrow();
		final Panel p = PanelDao.byId(panelId).orElseThrow();
		final String h = HtmlRenderer.rendern(t, p, true);

		assertTrue(h.startsWith("<!doctype html>"));
		assertTrue(h.contains("</html>"), "Dokument nicht geschlossen");
		assertTrue(h.contains("dorf.png"), "Der Anhang muss trotzdem auftauchen");
		assertFalse(h.contains("stecken in dieser Datei — sie bleibt für sich allein lesbar"),
				"Darf sich nicht als vollständig ausgeben");
		assertTrue(h.contains("Link nicht abrufbar"), "Der fehlende Anhang muss benannt sein");
	}

	@Test
	@DisplayName("Die Datei bleibt klein — das ist der Unterschied zu Ticket Tool")
	void bleibtKlein() {
		// Ticket Tool bettet Bilder als base64 ein und kommt so auf Dateien bis
		// 6,6 MB. Hier sind es Kilobyte, weil verlinkt statt eingebettet wird.
		final int bytes = html().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
		assertTrue(bytes < 30_000, "Unerwartet groß: " + bytes + " Bytes");
		assertTrue(bytes > 2_000, "Verdächtig klein: " + bytes + " Bytes");
	}
}
