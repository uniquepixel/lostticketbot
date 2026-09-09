package db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import model.GuildConfig;
import model.Panel;
import model.Ticket;

/**
 * Integrationstests gegen eine echte Postgres-Datenbank.
 *
 * Laufen nur, wenn TICKETBOT_DB_URL gesetzt ist — sonst werden sie
 * uebersprungen statt fehlzuschlagen, damit ein Build ohne Datenbank nicht rot
 * wird.
 *
 * Alle Testdaten haengen an einer eigenen guild_id und werden vorher wie
 * nachher entfernt. Der Produktivbestand wird nicht angefasst.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DatabaseIntegrationTest {

	private static final String GUILD = "test-guild-integration";
	private boolean verfuegbar;

	@BeforeAll
	void setUp() {
		final String url = System.getenv("TICKETBOT_DB_URL");
		verfuegbar = url != null && !url.isBlank();
		assumeTrue(verfuegbar, "TICKETBOT_DB_URL nicht gesetzt — Integrationstests uebersprungen");

		Database.init(url, System.getenv("TICKETBOT_DB_USER"),
				System.getenv().getOrDefault("TICKETBOT_DB_PASSWORD", ""));
		Database.applySchema();
		aufraeumen();
	}

	@AfterAll
	void tearDown() {
		if (verfuegbar) {
			aufraeumen();
			Database.shutdown();
		}
	}

	private void aufraeumen() {
		Database.update("DELETE FROM tickets WHERE guild_id = ?", GUILD);
		Database.update(
				"DELETE FROM panel_roles WHERE panel_id IN (SELECT id FROM panels WHERE guild_id = ?)",
				GUILD);
		Database.update("DELETE FROM panels WHERE guild_id = ?", GUILD);
		Database.update("DELETE FROM blacklist WHERE guild_id = ?", GUILD);
		Database.update("DELETE FROM guild_config WHERE guild_id = ?", GUILD);
	}

	private long neuesPanel(String name) {
		return PanelDao.create(GUILD, name);
	}

	// -----------------------------------------------------------------------

	@Test
	@DisplayName("Panel anlegen, lesen und aendern")
	void panelRundlauf() {
		final long id = neuesPanel("rundlauf");
		final Panel p = PanelDao.byId(id).orElseThrow();
		assertEquals("rundlauf", p.name());
		assertEquals(GUILD, p.guildId());
		assertTrue(p.active(), "Ein neues Panel ist standardmaessig aktiv");
		assertEquals(0, p.counter());

		PanelDao.set(id, "counter_padding", 2);
		assertEquals(2, PanelDao.byId(id).orElseThrow().counterPadding());

		PanelDao.set(id, "welcome_text", null);
		assertNull(PanelDao.byId(id).orElseThrow().welcomeText());
	}

	@Test
	@DisplayName("Nicht freigegebene Spaltennamen werden abgewiesen")
	void spaltenSchutz() {
		final long id = neuesPanel("spaltenschutz");
		assertThrows(IllegalArgumentException.class,
				() -> PanelDao.set(id, "name = name; DROP TABLE panels", "boesartig"));
		assertTrue(PanelDao.byId(id).isPresent(), "Die Tabelle steht noch");
	}

	@Test
	@DisplayName("Der Zaehler vergibt auch bei 50 gleichzeitigen Zugriffen keine Nummer doppelt")
	void zaehlerIstAtomar() throws Exception {
		final long panelId = neuesPanel("nebenlaeufig");
		final int gleichzeitig = 50;

		final Set<Integer> vergeben = Collections.synchronizedSet(new HashSet<>());
		final CountDownLatch start = new CountDownLatch(1);
		final CountDownLatch fertig = new CountDownLatch(gleichzeitig);
		final ExecutorService pool = Executors.newFixedThreadPool(16);

		for (int i = 0; i < gleichzeitig; i++) {
			pool.execute(() -> {
				try {
					start.await();
					vergeben.add(PanelDao.nextNumber(panelId));
				} catch (final InterruptedException e) {
					Thread.currentThread().interrupt();
				} finally {
					fertig.countDown();
				}
			});
		}
		start.countDown();
		assertTrue(fertig.await(30, TimeUnit.SECONDS), "Die Zugriffe sind nicht durchgelaufen");
		pool.shutdown();

		assertEquals(gleichzeitig, vergeben.size(),
				"Jede Nummer darf nur einmal vergeben werden — sonst kollidieren zwei Kanalnamen");
		assertEquals(gleichzeitig, PanelDao.byId(panelId).orElseThrow().counter());
	}

	@Test
	@DisplayName("Offene Tickets werden pro Nutzer und pro Panel richtig gezaehlt")
	void offeneTicketsZaehlen() {
		final long panelA = neuesPanel("zaehlen-a");
		final long panelB = neuesPanel("zaehlen-b");
		final String nutzer = "111";

		final long t1 = TicketDao.create(GUILD, panelA, 1, "kanal-1", "kanal-1", nutzer);
		TicketDao.create(GUILD, panelB, 1, "kanal-2", "kanal-2", nutzer);
		TicketDao.create(GUILD, panelA, 2, "kanal-3", "kanal-3", "222");

		assertEquals(2, TicketDao.countOpenByOwner(GUILD, nutzer), "serverweit");
		assertEquals(1, TicketDao.countOpenByOwnerAndPanel(panelA, nutzer), "pro Panel");
		assertEquals(1, TicketDao.countOpenByOwner(GUILD, "222"));

		// Geschlossene zaehlen nicht mehr mit — sonst koennte niemand je wieder
		// ein Ticket oeffnen, der einmal das Limit erreicht hatte.
		TicketDao.close(t1, "999", "erledigt", "kanal-1-closed");
		assertEquals(1, TicketDao.countOpenByOwner(GUILD, nutzer));
		assertEquals(0, TicketDao.countOpenByOwnerAndPanel(panelA, nutzer));
	}

	@Test
	@DisplayName("Schliessen, Betreuen und Wiedereroeffnen fuehren den Zustand korrekt")
	void lebenszyklus() {
		final long panelId = neuesPanel("lebenszyklus");
		final long id = TicketDao.create(GUILD, panelId, 7, "kanal-lz", "kanal-lz", "111");

		assertTrue(TicketDao.byId(id).orElseThrow().isOpen());

		TicketDao.setClaim(id, "betreuer");
		assertEquals("betreuer", TicketDao.byId(id).orElseThrow().claimedBy());

		TicketDao.close(id, "schliesser", "fertig", "kanal-lz-closed");
		final Ticket zu = TicketDao.byId(id).orElseThrow();
		assertFalse(zu.isOpen());
		assertEquals(Ticket.Status.CLOSED, zu.status());
		assertEquals("schliesser", zu.closedBy());
		assertEquals("kanal-lz-closed", zu.channelName());

		TicketDao.reopen(id, "kanal-lz");
		final Ticket wiederOffen = TicketDao.byId(id).orElseThrow();
		assertTrue(wiederOffen.isOpen());
		assertNull(wiederOffen.closedBy(), "Beim Wiedereroeffnen wird der Schliesser geloescht");
	}

	@Test
	@DisplayName("Rollen trennen nach Art und lassen sich einzeln entfernen")
	void rollen() {
		final long panelId = neuesPanel("rollen");
		PanelDao.addRole(panelId, "rolle-1", "support");
		PanelDao.addRole(panelId, "rolle-2", "support");
		PanelDao.addRole(panelId, "rolle-1", "ping");
		PanelDao.addRole(panelId, "rolle-1", "support"); // doppelt, darf nichts tun

		assertEquals(2, PanelDao.supportRoles(panelId).size());
		assertEquals(List.of("rolle-1"), PanelDao.pingRoles(panelId));

		PanelDao.removeRole(panelId, "rolle-1", "support");
		assertEquals(List.of("rolle-2"), PanelDao.supportRoles(panelId));
		assertEquals(List.of("rolle-1"), PanelDao.pingRoles(panelId),
				"Die Ping-Zuordnung bleibt unberuehrt");
	}

	@Test
	@DisplayName("Serverkonfiguration entsteht beim ersten Zugriff, 0 heisst unbegrenzt")
	void guildConfig() {
		final GuildConfig frisch = GuildConfigDao.get(GUILD);
		assertEquals(GUILD, frisch.guildId());
		assertFalse(frisch.hasGlobalLimit(), "Ohne Angabe gilt kein Limit");

		GuildConfigDao.setGlobalLimit(GUILD, 1);
		final GuildConfig gesetzt = GuildConfigDao.get(GUILD);
		assertTrue(gesetzt.hasGlobalLimit());
		assertEquals(1, gesetzt.globalMaxOpenTickets());

		GuildConfigDao.setGlobalLimit(GUILD, 0);
		assertFalse(GuildConfigDao.get(GUILD).hasGlobalLimit(), "0 bedeutet unbegrenzt");
	}

	@Test
	@DisplayName("Ein Kanal gehoert zu genau einem Ticket")
	void kanalIstEindeutig() {
		final long panelId = neuesPanel("kanal-eindeutig");
		TicketDao.create(GUILD, panelId, 1, "kanal-eindeutig-1", "kanal-eindeutig-1", "111");

		assertTrue(TicketDao.byChannel("kanal-eindeutig-1").isPresent());
		assertTrue(TicketDao.byChannel("gibt-es-nicht").isEmpty());
	}

	@Test
	@DisplayName("Dieselbe Nachricht zweimal zu erfassen legt keine zweite Zeile an")
	void transcriptOhneDuplikate() {
		final long panelId = neuesPanel("transcript");
		final long ticketId = TicketDao.create(GUILD, panelId, 1, "kanal-tr", "kanal-tr", "111");

		final String sql = "INSERT INTO ticket_messages "
				+ "(ticket_id, message_id, author_id, author_name, author_bot, content, sent_at) "
				+ "VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP) "
				+ "ON CONFLICT (ticket_id, message_id) DO NOTHING";

		final long ersteId = Database.insertIgnoringConflict(sql, ticketId, "msg-1", "111", "wer", false, "hallo");
		assertTrue(ersteId > 0, "Die erste Erfassung legt eine Zeile an");

		final long zweiteId = Database.insertIgnoringConflict(sql, ticketId, "msg-1", "111", "wer", false, "hallo");
		assertEquals(0L, zweiteId,
				"Die zweite Erfassung meldet 0 — daran erkennt der Recorder, dass er die Anhaenge "
						+ "nicht noch einmal spiegeln muss");

		assertEquals(1, Database.count(
				"SELECT COUNT(*) FROM ticket_messages WHERE ticket_id = ?", ticketId));
	}
}
