package ticket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

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
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;

/**
 * Was passiert, wenn jemand einen Ticketkanal von Hand in Discord loescht?
 *
 * Der Fall aus dem Betrieb, viermal von Hand berichtigt: das Ticket stand
 * danach weiter als "offen" in der Datenbank, im Lagebericht tauchten wartende
 * Bewerber auf, die es nicht mehr gab, und der Verlauf lag nur noch in der
 * Datenbank — dem Teil, den dieses System ausdruecklich als wegwerfbar
 * behandelt.
 *
 * Geprueft werden beide Wege, auf denen der Bot das jetzt bemerkt: das
 * ChannelDeleteEvent im Betrieb und der Abgleich beim Start, der die Luecke
 * eines Neustarts schliesst.
 *
 * Laeuft nur mit TICKETBOT_TOKEN, TICKETBOT_TEST_GUILD und Datenbankzugang;
 * sonst uebersprungen. Alles Angelegte wird wieder entfernt.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KanalGeloeschtTest {

	private static final String TITEL_VON_HAND = "Ticketkanal von Hand gelöscht";

	private JDA jda;
	private Guild guild;
	private Category kategorie;
	private TextChannel logKanal;
	private long panelId;
	private boolean verfuegbar;

	/** Alles, was der Test in Discord angelegt hat — auch wenn er unterwegs scheitert. */
	private final List<String> angelegteKanaele = new ArrayList<>();

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

		// Der Waechter haengt hier ausdruecklich im Gateway: der Test soll den
		// echten Weg gehen, vom Loeschen in Discord bis in die Datenbank.
		jda = JDABuilder.createDefault(token)
				.addEventListeners(new TicketKanalWaechter())
				.build()
				.awaitReady();
		guild = jda.getGuildById(guildId);
		assertNotNull(guild, "Der Bot ist nicht auf dem Testserver");

		kategorie = guild.createCategory("weg-test").complete();
		logKanal = guild.createTextChannel("weg-log").setParent(kategorie).complete();

		panelId = PanelDao.create(guild.getId(), "weg-" + System.currentTimeMillis());
		PanelDao.set(panelId, "category_opened", kategorie.getId());
		PanelDao.set(panelId, "name_pattern_open", "weg-{count}");
		PanelDao.set(panelId, "name_pattern_closed", "weg-closed-{count}");
		PanelDao.set(panelId, "log_channel_id", logKanal.getId());

		// Reste frueherer Laeufe: Ticketzeilen des Testservers, deren Kanal es
		// nicht mehr gibt. Ohne das zaehlt der Abgleich sie mit und stoesst an
		// seine Sicherheitsgrenze, statt den Fall dieses Tests zu bearbeiten.
		for (final Ticket alt : TicketDao.nichtGeloescht(guild.getId())) {
			if (guild.getGuildChannelById(alt.channelId()) == null) {
				Database.update("DELETE FROM tickets WHERE id = ?", alt.id());
			}
		}
	}

	@AfterAll
	void tearDown() {
		if (!verfuegbar) {
			return;
		}
		for (final String id : angelegteKanaele) {
			final TextChannel kanal = guild.getTextChannelById(id);
			if (kanal != null) {
				kanal.delete().complete();
			}
		}
		Database.update("DELETE FROM tickets WHERE panel_id = ?", panelId);
		Database.update("DELETE FROM panel_roles WHERE panel_id = ?", panelId);
		Database.update("DELETE FROM panels WHERE id = ?", panelId);
		if (logKanal != null) {
			logKanal.delete().complete();
		}
		if (kategorie != null) {
			kategorie.delete().complete();
		}
		Database.shutdown();
		jda.shutdownNow();
	}

	// -----------------------------------------------------------------------

	@Test
	@DisplayName("Wird der Kanal in Discord gelöscht, merkt der Bot es und stellt den Status um")
	void kanalVonHandGeloescht() {
		final TextChannel kanal = kanalAnlegen("weg-0001");
		final long ticketId = TicketDao.create(guild.getId(), panelId, 1, kanal.getId(),
				kanal.getName(), guild.getSelfMember().getId());
		nachricht(ticketId, "m-bot-1", true, "Willkommen im Ticket.");
		assertTrue(TicketDao.byId(ticketId).orElseThrow().isOpen());

		// Genau der Fall: nicht ueber den Loeschknopf, sondern in Discord.
		kanal.delete().reason("Test: von Hand gelöscht").complete();
		angelegteKanaele.remove(kanal.getId());

		final Ticket nachher = warteAufStatus(ticketId, Ticket.Status.DELETED);
		assertEquals(Ticket.Status.DELETED, nachher.status(),
				"Der Bot hat das Löschen des Kanals nicht bemerkt");
		assertEquals(TicketKanalWaechter.GRUND, nachher.closeReason());
		assertNotNull(nachher.closedAt(),
				"Ein Ticket ohne Schließzeitpunkt fehlt in jeder Statistik");

		final Message eintrag = logEintrag(TITEL_VON_HAND, "weg-0001");
		assertNotNull(eintrag, "Im Log-Kanal steht kein Hinweis, dass es diesen Kanal gab");

		// In diesem Ticket hat nur der Bot geschrieben — da ist nichts zu
		// retten, und es entsteht bewusst kein Archiv-Post. Die Fußnote muss
		// das sagen und nicht einen Fehler behaupten, den es nicht gibt.
		final MessageEmbed embed = eintrag.getEmbeds().stream()
				.filter(e -> TITEL_VON_HAND.equals(e.getTitle()))
				.findFirst().orElseThrow();
		assertNotNull(embed.getFooter());
		assertEquals("kein Archiv — es war nichts aufgezeichnet", embed.getFooter().getText(),
				"Die Fußnote behauptet etwas anderes als den wahren Grund");
	}

	@Test
	@DisplayName("Der Abgleich beim Start rettet den Verlauf eines verwaisten Tickets")
	void abgleichBeimStart() throws Exception {
		// Ein Kanal, den es nicht gibt — genau die Lage nach einem Neustart, in
		// dessen Sekunden jemand geloescht hat: kein Event, nur eine Zeile in
		// der Datenbank, zu der kein Kanal mehr gehoert.
		final long ticketId = TicketDao.create(guild.getId(), panelId, 2, "999999999999999999",
				"weg-0002", guild.getSelfMember().getId());
		nachricht(ticketId, "m-1", false, "Ich bewerbe mich für LOST Family.");
		nachricht(ticketId, "m-2", false, "Rathaus 15, danke!");

		TicketKanalWaechter.nachholen(guild);

		final Ticket nachher = TicketDao.byId(ticketId).orElseThrow();
		assertEquals(Ticket.Status.DELETED, nachher.status(),
				"Der Abgleich hat das verwaiste Ticket übersehen");
		assertEquals(TicketKanalWaechter.GRUND, nachher.closeReason());

		// Der eigentliche Punkt: der Verlauf ist nicht mit dem Kanal
		// verschwunden, sondern haengt lesbar am Log-Eintrag.
		final Message eintrag = logEintrag(TITEL_VON_HAND, "weg-0002");
		assertNotNull(eintrag, "Im Log-Kanal fehlt der Eintrag zum verwaisten Ticket");
		assertEquals(1, eintrag.getAttachments().size(), "Der gerettete Verlauf fehlt als Datei");

		final String inhalt;
		try (var in = eintrag.getAttachments().get(0).getProxy().download().join()) {
			inhalt = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		assertTrue(inhalt.contains("Ich bewerbe mich für LOST Family."),
				"Die Nachrichten aus der Datenbank stehen nicht in der geretteten Datei");
	}

	@Test
	@DisplayName("Löscht der Bot selbst, ist die Löschung als eigene vermerkt")
	void eigeneLoeschungIstVermerkt() {
		final TextChannel kanal = kanalAnlegen("weg-0003");
		final long ticketId = TicketDao.create(guild.getId(), panelId, 3, kanal.getId(),
				kanal.getName(), guild.getSelfMember().getId());
		final Panel panel = PanelDao.byId(panelId).orElseThrow();

		TicketService.delete(guild, TicketDao.byId(ticketId).orElseThrow(), panel,
				guild.getSelfMember().getId());
		angelegteKanaele.remove(kanal.getId());

		// Auf diesen Vermerk stuetzt sich der Waechter, wenn das eigene
		// ChannelDeleteEvent zurueckkommt: ohne ihn wuerde er jedes ueber den
		// Loeschknopf entfernte Ticket ein zweites Mal archivieren und einen
		// zweiten Log-Eintrag schreiben.
		assertTrue(TicketService.warEigeneLoeschung(kanal.getId()),
				"Die eigene Löschung ist nicht vermerkt — der Wächter würde doppelt arbeiten");
		assertFalse(TicketService.warEigeneLoeschung("777777777777777777"),
				"Ein fremder Kanal darf nicht als eigene Löschung gelten");
		assertEquals(Ticket.Status.DELETED, TicketDao.byId(ticketId).orElseThrow().status());

		// Kein Gegentest auf einen ausbleibenden zweiten Log-Eintrag: auf dem
		// Testserver haengt auch der laufende Produktivbot am selben Gateway
		// und sieht dasselbe Event. Was hier zaehlt, ist der Vermerk.
	}

	// -----------------------------------------------------------------------

	private TextChannel kanalAnlegen(String name) {
		final TextChannel kanal = guild.createTextChannel(name).setParent(kategorie).complete();
		angelegteKanaele.add(kanal.getId());
		return kanal;
	}

	private void nachricht(long ticketId, String messageId, boolean bot, String text) {
		Database.update(
				"INSERT INTO ticket_messages (ticket_id, message_id, author_id, author_name, "
						+ "author_bot, content, sent_at) VALUES (?, ?, '4711', 'tester', ?, ?, "
						+ "CURRENT_TIMESTAMP)",
				ticketId, messageId, bot, text);
	}

	/** Der Waechter arbeitet in einem eigenen Thread; hier wird auf ihn gewartet. */
	private Ticket warteAufStatus(long ticketId, Ticket.Status erwartet) {
		Ticket ticket = TicketDao.byId(ticketId).orElseThrow();
		for (int versuch = 0; versuch < 60 && ticket.status() != erwartet; versuch++) {
			schlafe(500);
			ticket = TicketDao.byId(ticketId).orElseThrow();
		}
		return ticket;
	}

	/**
	 * Wartet auf den Log-Eintrag zu genau diesem Ticket.
	 *
	 * Der Titel allein reicht nicht: die Tests dieser Klasse schreiben
	 * nacheinander in denselben Kanal, und der Eintrag des vorigen wuerde jeden
	 * folgenden bestaetigen, ohne dass etwas passiert waere.
	 */
	private Message logEintrag(String titel, String ticketName) {
		for (int versuch = 0; versuch < 30; versuch++) {
			final var treffer = logKanal.getHistory().retrievePast(20).complete().stream()
					.filter(m -> m.getEmbeds().stream()
							.anyMatch(e -> titel.equals(e.getTitle()) && nenntTicket(e, ticketName)))
					.findFirst();
			if (treffer.isPresent()) {
				return treffer.get();
			}
			schlafe(500);
		}
		return null;
	}

	private static boolean nenntTicket(MessageEmbed embed, String ticketName) {
		return embed.getFields().stream()
				.anyMatch(f -> "Ticket".equals(f.getName()) && ticketName.equals(f.getValue()));
	}

	private void schlafe(long ms) {
		try {
			Thread.sleep(ms);
		} catch (final InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
