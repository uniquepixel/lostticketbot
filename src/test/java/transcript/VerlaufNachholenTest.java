package transcript;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import db.Database;
import db.PanelDao;
import db.TicketDao;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;

/**
 * Was in einem Ticket geschrieben wird, waehrend der Bot nicht zuhoert, muss
 * nach dem Start trotzdem im Verlauf stehen.
 *
 * Der Fall aus dem Betrieb: in der Nacht zum 19.11.2026 stand der Server elf
 * Stunden; zwei Nachrichten aus offenen Tickets fehlten danach in der
 * Datenbank und waeren damit auch nie im Archiv gelandet.
 *
 * Der Recorder haengt hier bewusst NICHT im Gateway, damit der Abgleich beim
 * Start nicht ueber alle Server laeuft; die Luecke wird nachgestellt, indem
 * die Zeilen aus der Datenbank genommen werden.
 *
 * Laeuft nur mit TICKETBOT_TOKEN, TICKETBOT_TEST_GUILD und Datenbankzugang;
 * sonst uebersprungen. Alles Angelegte wird wieder entfernt.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VerlaufNachholenTest {

	private JDA jda;
	private Guild guild;
	private Category kategorie;
	private TextChannel kanal;
	private long panelId;
	private boolean verfuegbar;

	@BeforeAll
	void setUp() throws Exception {
		final String token = System.getenv("TICKETBOT_TOKEN");
		final String guildId = System.getenv("TICKETBOT_TEST_GUILD");
		final String dbUrl = System.getenv("TICKETBOT_DB_URL");
		verfuegbar = token != null && !token.isBlank()
				&& guildId != null && !guildId.isBlank()
				&& dbUrl != null && !dbUrl.isBlank();
		assumeTrue(verfuegbar, "TICKETBOT_TOKEN/TEST_GUILD/DB_URL fehlen — uebersprungen");

		Database.init(dbUrl, System.getenv("TICKETBOT_DB_USER"),
				System.getenv().getOrDefault("TICKETBOT_DB_PASSWORD", ""));
		Database.applySchema();

		jda = JDABuilder.createDefault(token).build().awaitReady();
		guild = jda.getGuildById(guildId);
		assertNotNull(guild, "Der Bot ist nicht auf dem Testserver");

		kategorie = guild.createCategory("nachhol-test").complete();
		kanal = guild.createTextChannel("nachhol-0001").setParent(kategorie).complete();
		panelId = PanelDao.create(guild.getId(), "nachhol-" + System.currentTimeMillis());
	}

	@AfterAll
	void tearDown() {
		if (!verfuegbar) {
			return;
		}
		Database.update("DELETE FROM tickets WHERE panel_id = ?", panelId);
		Database.update("DELETE FROM panels WHERE id = ?", panelId);
		if (kanal != null) {
			kanal.delete().complete();
		}
		if (kategorie != null) {
			kategorie.delete().complete();
		}
		Database.shutdown();
		jda.shutdownNow();
	}

	@Test
	@DisplayName("Nachrichten aus einer Ausfallzeit stehen nach dem Abgleich im Verlauf, in der richtigen Reihenfolge und nur einmal")
	void verpassteNachrichtenWerdenNachgetragen() {
		final long ticketId = TicketDao.create(guild.getId(), panelId, 1, kanal.getId(),
				kanal.getName(), guild.getSelfMember().getId());
		final TranscriptRecorder recorder = new TranscriptRecorder();

		final Message erste = kanal.sendMessage("Hallo, ich habe eine Frage.").complete();
		final Message zweite = kanal.sendMessage("Ist noch jemand da?").complete();
		final Message dritte = kanal.sendMessage("Dann bis morgen.").complete();

		// Der laufende Bot sitzt mit auf dem Testserver und schreibt in dieselbe
		// Datenbank mit. Erst abwarten, bis er fertig ist (laeuft er nicht,
		// kommt nichts), dann den Ausfall nachstellen: die erste Nachricht hat
		// der Bot noch mitbekommen, die beiden anderen nicht.
		for (int versuch = 0; versuch < 10 && verlauf(ticketId).size() < 3; versuch++) {
			schlafe(500);
		}
		schlafe(500);
		recorder.erfasse(ticketId, erste);
		Database.update("DELETE FROM ticket_messages WHERE ticket_id = ? AND message_id IN (?, ?)",
				ticketId, zweite.getId(), dritte.getId());
		assertEquals(List.of(erste.getId()), verlauf(ticketId));

		recorder.nachholen(guild);

		assertEquals(List.of(erste.getId(), zweite.getId(), dritte.getId()), verlauf(ticketId),
				"Die verpassten Nachrichten fehlen im Verlauf oder stehen in falscher Reihenfolge");
		assertEquals("Dann bis morgen.", Database.query(
				"SELECT content FROM ticket_messages WHERE ticket_id = ? AND message_id = ?",
				rs -> rs.getString(1), ticketId, dritte.getId()).get(0));

		// Ein zweiter Abgleich (naechster Neustart) darf nichts verdoppeln.
		recorder.nachholen(guild);
		assertEquals(3, verlauf(ticketId).size(), "Der zweite Abgleich hat Nachrichten doppelt eingetragen");
	}

	private static void schlafe(long ms) {
		try {
			Thread.sleep(ms);
		} catch (final InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private static List<String> verlauf(long ticketId) {
		return Database.query(
				"SELECT message_id FROM ticket_messages WHERE ticket_id = ? ORDER BY sent_at, id",
				rs -> rs.getString(1), ticketId);
	}
}
