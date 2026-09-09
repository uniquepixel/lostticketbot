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

/**
 * Erkennt das Archiv, dass ihm etwas fehlt?
 *
 * Der Anlass ist eine Luecke, die erst beim Loeschen sichtbar geworden waere:
 * Beim Schliessen verliert der Eroeffner das Schreibrecht, das Team aber nicht,
 * und der Mitschnitt laeuft weiter. Ein Archiv vom Schliesszeitpunkt kennt
 * diese spaeteren Nachrichten nicht. Solange der Kanal steht, faellt das nicht
 * auf — wird er geloescht, waeren sie nur noch in der Datenbank, und die ist in
 * diesem System ausdruecklich der wegwerfbare Teil.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ArchivAktualitaetTest {

	private static final String GUILD = "test-guild-archiv";
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

		panelId = PanelDao.create(GUILD, "archiv");
		ticketId = TicketDao.create(GUILD, panelId, 1, "kanal-archiv", "archiv-0001", "111");
	}

	@AfterAll
	void tearDown() {
		if (verfuegbar) {
			aufraeumen();
			Database.shutdown();
		}
	}

	private void aufraeumen() {
		Database.update("DELETE FROM transcript_archives WHERE ticket_id IN "
				+ "(SELECT id FROM tickets WHERE guild_id = ?)", GUILD);
		Database.update("DELETE FROM ticket_messages WHERE ticket_id IN "
				+ "(SELECT id FROM tickets WHERE guild_id = ?)", GUILD);
		Database.update("DELETE FROM tickets WHERE guild_id = ?", GUILD);
		Database.update("DELETE FROM panels WHERE guild_id = ?", GUILD);
	}

	/** Eine Nachricht mit ausdruecklichem Zeitpunkt. */
	private void nachricht(String id, String versatz) {
		Database.update(
				"INSERT INTO ticket_messages (ticket_id, message_id, author_id, author_name, "
						+ "author_bot, content, sent_at) VALUES (?, ?, '222', 'tester', false, 'text', "
						+ "CURRENT_TIMESTAMP + INTERVAL '" + versatz + "')",
				ticketId, id);
	}

	private void archivieren(String versatz) {
		Database.update(
				"INSERT INTO transcript_archives (ticket_id, storage_channel_id, storage_message_id, "
						+ "archived_at) VALUES (?, '1', '2', CURRENT_TIMESTAMP + INTERVAL '" + versatz + "') "
						+ "ON CONFLICT (ticket_id) DO UPDATE SET archived_at = EXCLUDED.archived_at",
				ticketId);
	}

	// -----------------------------------------------------------------------

	@Test
	@DisplayName("Ohne Archiv ist nichts aktuell")
	void ohneArchiv() {
		Database.update("DELETE FROM transcript_archives WHERE ticket_id = ?", ticketId);
		assertFalse(TranscriptArchiver.archivIstAktuell(ticketId));
		assertFalse(TranscriptArchiver.istArchiviert(ticketId));
	}

	@Test
	@DisplayName("Archiv nach der letzten Nachricht: aktuell")
	void archivIstJuenger() {
		Database.update("DELETE FROM ticket_messages WHERE ticket_id = ?", ticketId);
		nachricht("m1", "-10 minutes");
		nachricht("m2", "-5 minutes");
		archivieren("-1 minute");

		assertTrue(TranscriptArchiver.istArchiviert(ticketId));
		assertTrue(TranscriptArchiver.archivIstAktuell(ticketId),
				"Nach der letzten Nachricht archiviert — da fehlt nichts");
	}

	@Test
	@DisplayName("Nachricht nach dem Archivieren: nicht mehr aktuell")
	void nachrichtNachArchiv() {
		Database.update("DELETE FROM ticket_messages WHERE ticket_id = ?", ticketId);
		nachricht("m1", "-10 minutes");
		archivieren("-5 minutes");
		// Genau der Fall: nach dem Schliessen schreibt das Team noch etwas.
		nachricht("m2", "-1 minute");

		assertTrue(TranscriptArchiver.istArchiviert(ticketId),
				"Ein Archiv gibt es — es ist nur unvollstaendig");
		assertFalse(TranscriptArchiver.archivIstAktuell(ticketId),
				"Die spätere Nachricht steht in keinem Archiv");
	}

	@Test
	@DisplayName("Ticket ohne jede Nachricht gilt als vollständig archiviert")
	void leeresTicket() {
		Database.update("DELETE FROM ticket_messages WHERE ticket_id = ?", ticketId);
		archivieren("-1 minute");
		assertTrue(TranscriptArchiver.archivIstAktuell(ticketId),
				"Nichts aufgezeichnet heißt: nichts fehlt");
	}
}
