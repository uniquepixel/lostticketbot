package ticket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import db.Database;
import db.GuildConfigDao;
import db.PanelDao;
import db.TicketDao;
import model.Panel;
import model.Ticket;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.PermissionOverride;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.requests.GatewayIntent;

/**
 * Ende-zu-Ende gegen echtes Discord.
 *
 * Ein Bot kann seine eigenen Knoepfe nicht druecken — deshalb wird hier nicht
 * die Interaktion nachgestellt, sondern {@link TicketService} direkt
 * aufgerufen, mit dem Bot selbst als Eroeffner. Damit laeuft alles, was hinter
 * dem Knopf haengt: Kanal anlegen, Kategorie waehlen, Berechtigungen setzen,
 * Datenbank schreiben, umbenennen, schliessen.
 *
 * Laeuft nur mit TICKETBOT_TOKEN, TICKETBOT_TEST_GUILD und Datenbankzugang;
 * sonst uebersprungen. Alle angelegten Kanaele werden wieder entfernt.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DiscordEndToEndTest {

	private JDA jda;
	private Guild guild;
	private Category kategorie;
	private TextChannel logKanal;
	private long panelId;
	private String kanalId;
	private boolean verfuegbar;

	@BeforeAll
	void setUp() throws Exception {
		final String token = System.getenv("TICKETBOT_TOKEN");
		final String guildId = System.getenv("TICKETBOT_TEST_GUILD");
		final String dbUrl = System.getenv("TICKETBOT_DB_URL");
		verfuegbar = token != null && !token.isBlank()
				&& guildId != null && !guildId.isBlank()
				&& dbUrl != null && !dbUrl.isBlank();
		assumeTrue(verfuegbar, "TICKETBOT_TOKEN/TEST_GUILD/DB_URL fehlen — Ende-zu-Ende uebersprungen");

		Database.init(dbUrl, System.getenv("TICKETBOT_DB_USER"),
				System.getenv().getOrDefault("TICKETBOT_DB_PASSWORD", ""));
		Database.applySchema();

		jda = JDABuilder.createDefault(token)
				.enableIntents(GatewayIntent.GUILD_MEMBERS)
				.build()
				.awaitReady();
		guild = jda.getGuildById(guildId);
		assertNotNull(guild, "Der Bot ist nicht auf dem Testserver");

		// Globales Limit ausschalten, damit dieser Test nicht an einem
		// Ueberbleibsel aus frueheren Laeufen scheitert.
		GuildConfigDao.setGlobalLimit(guild.getId(), 0);

		kategorie = guild.createCategory("e2e-test").complete();
		logKanal = guild.createTextChannel("e2e-log").setParent(kategorie).complete();

		panelId = PanelDao.create(guild.getId(), "e2e-" + System.currentTimeMillis());
		PanelDao.set(panelId, "category_opened", kategorie.getId());
		PanelDao.set(panelId, "name_pattern_open", "e2e-{count}");
		PanelDao.set(panelId, "name_pattern_closed", "e2e-closed-{count}");
		PanelDao.set(panelId, "counter", 40);
		PanelDao.set(panelId, "counter_padding", 4);
		PanelDao.set(panelId, "max_open_per_user", 1);
		PanelDao.set(panelId, "log_channel_id", logKanal.getId());
	}

	@AfterAll
	void tearDown() {
		if (!verfuegbar) {
			return;
		}
		if (kanalId != null) {
			final TextChannel kanal = guild.getTextChannelById(kanalId);
			if (kanal != null) {
				kanal.delete().complete();
			}
			TicketDao.byChannel(kanalId).ifPresent(t -> Database.update("DELETE FROM tickets WHERE id = ?", t.id()));
		}
		if (logKanal != null) {
			logKanal.delete().complete();
		}
		if (kategorie != null) {
			kategorie.delete().complete();
		}
		Database.update("DELETE FROM panel_roles WHERE panel_id = ?", panelId);
		Database.update("DELETE FROM panels WHERE id = ?", panelId);
		Database.shutdown();
		jda.shutdownNow();
	}

	// -----------------------------------------------------------------------

	@Test
	@Order(1)
	@DisplayName("Ticket oeffnen legt Kanal, Kategorie, Rechte und Datenbankzeile korrekt an")
	void oeffnen() {
		final Panel panel = PanelDao.byId(panelId).orElseThrow();
		final Member bot = guild.getSelfMember();

		final TicketService.OpenResult result = TicketService.open(guild, panel, bot);
		assertTrue(result.ok(), () -> "Oeffnen fehlgeschlagen: " + result.error());
		kanalId = result.channel().getId();

		final TextChannel kanal = result.channel();
		assertEquals("e2e-0041", kanal.getName(), "Zaehler stand auf 40, Auffuellung auf 4 Stellen");
		assertNotNull(kanal.getParentCategory());
		assertEquals(kategorie.getId(), kanal.getParentCategory().getId());

		// Der entscheidende Punkt: niemand ausser den Beteiligten darf hinein.
		final PermissionOverride everyone = kanal.getPermissionOverride(guild.getPublicRole());
		assertNotNull(everyone, "Fuer @everyone muss eine Ueberschreibung gesetzt sein");
		assertTrue(everyone.getDenied().contains(Permission.VIEW_CHANNEL),
				"@everyone darf das Ticket nicht sehen");

		final PermissionOverride eroeffner = kanal.getPermissionOverride(bot);
		assertNotNull(eroeffner);
		assertTrue(eroeffner.getAllowed().containsAll(
				EnumSet.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_HISTORY)),
				"Der Eroeffner muss lesen und schreiben duerfen");

		final Ticket ticket = TicketDao.byChannel(kanal.getId()).orElseThrow();
		assertEquals(41, ticket.number());
		assertEquals(bot.getId(), ticket.ownerId());
		assertTrue(ticket.isOpen());
		assertEquals("e2e-0041", ticket.channelName());
	}

	@Test
	@Order(2)
	@DisplayName("Das Panel-Limit greift beim zweiten Versuch")
	void limitGreift() {
		final Panel panel = PanelDao.byId(panelId).orElseThrow();
		final TicketService.OpenResult zweiter = TicketService.open(guild, panel, guild.getSelfMember());

		assertFalse(zweiter.ok(), "Ein zweites Ticket darf nicht entstehen");
		assertNotNull(zweiter.error());
		// Der Zaehler darf dabei nicht weiterlaufen: abgewiesen heisst, es wurde
		// gar nichts angelegt.
		assertEquals(41, PanelDao.byId(panelId).orElseThrow().counter(),
				"Ein abgewiesener Versuch darf keine Nummer verbrauchen");
	}

	@Test
	@Order(3)
	@DisplayName("Schliessen benennt um und entzieht das Schreibrecht, laesst aber Lesezugriff")
	void schliessen() {
		final Panel panel = PanelDao.byId(panelId).orElseThrow();
		final Ticket ticket = TicketDao.byChannel(kanalId).orElseThrow();

		TicketService.close(guild, ticket, panel, guild.getSelfMember().getId(), "Test");

		final TextChannel kanal = guild.getTextChannelById(kanalId);
		assertNotNull(kanal);
		assertEquals("e2e-closed-0041", kanal.getName());

		final PermissionOverride eroeffner = kanal.getPermissionOverride(guild.getSelfMember());
		assertNotNull(eroeffner);
		assertTrue(eroeffner.getAllowed().contains(Permission.VIEW_CHANNEL),
				"Der Eroeffner soll nachlesen koennen, was besprochen wurde");
		assertTrue(eroeffner.getDenied().contains(Permission.MESSAGE_SEND),
				"Aber nicht mehr schreiben");

		final Ticket zu = TicketDao.byChannel(kanalId).orElseThrow();
		assertFalse(zu.isOpen());
		assertEquals("e2e-closed-0041", zu.channelName());

		// Die Schliessnachricht bleibt im Kanal stehen und traegt die Knoepfe.
		TicketInteractions.postCloseNotice(kanal, zu, guild.getSelfMember().getId());

		net.dv8tion.jda.api.entities.Message notiz = null;
		for (int versuch = 0; versuch < 20 && notiz == null; versuch++) {
			notiz = kanal.getHistory().retrievePast(5).complete().stream()
					.filter(m -> m.getEmbeds().stream()
							.anyMatch(e -> "Ticket geschlossen".equals(e.getTitle())))
					.findFirst().orElse(null);
			if (notiz == null) {
				try {
					Thread.sleep(500);
				} catch (final InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
		}
		assertNotNull(notiz, "Im geschlossenen Ticket steht keine Schliessnachricht");

		// Entscheidend: eine gewoehnliche Kanalnachricht, keine fluechtige
		// Antwort — sie muss fuer alle im Ticket sichtbar bleiben.
		assertFalse(notiz.isEphemeral(), "Die Schliessnachricht darf nicht nur der Klickende sehen");

		final List<String> knoepfe = notiz.getButtons().stream()
				.map(net.dv8tion.jda.api.interactions.components.buttons.Button::getId)
				.toList();
		assertEquals(3, knoepfe.size(), "Erwartet: Transcript, Wieder öffnen, Löschen");
		assertTrue(knoepfe.contains(TicketInteractions.TRANSCRIPT_PREFIX + zu.id()));
		assertTrue(knoepfe.contains(TicketInteractions.REOPEN_PREFIX + zu.id()));
		assertTrue(knoepfe.contains(TicketInteractions.DELETE_PREFIX + zu.id()),
				"Der Löschknopf fehlt — genau der soll den Befehl ersetzen");
	}

	@Test
	@Order(4)
	@DisplayName("Der Log-Eintrag traegt den lesbaren Verlauf als Datei — wie bei Ticket Tool")
	void logEintragMitDatei() {
		final Panel panel = PanelDao.byId(panelId).orElseThrow();
		final Ticket ticket = TicketDao.byId(TicketDao.byChannel(kanalId).orElseThrow().id()).orElseThrow();

		transcript.TranscriptArchiver.archive(guild, ticket, panel, guild.getSelfMember().getId());

		// postLog schickt asynchron; auf die Nachricht warten statt zu raten.
		net.dv8tion.jda.api.entities.Message eintrag = null;
		for (int versuch = 0; versuch < 20 && eintrag == null; versuch++) {
			final var neueste = logKanal.getHistory().retrievePast(5).complete();
			eintrag = neueste.stream()
					.filter(m -> !m.getEmbeds().isEmpty())
					.findFirst().orElse(null);
			if (eintrag == null) {
				try {
					Thread.sleep(500);
				} catch (final InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
		}

		assertNotNull(eintrag, "Im Log-Kanal steht kein Eintrag");
		assertEquals(1, eintrag.getAttachments().size(), "Die Transcript-Datei fehlt am Log-Eintrag");
		final var datei = eintrag.getAttachments().get(0);
		assertTrue(datei.getFileName().endsWith(".html"),
				"Erwartet wird eine HTML-Datei, nicht " + datei.getFileName());
		assertTrue(datei.getFileName().contains(ticket.channelName()),
				"Der Dateiname soll das Ticket benennen: " + datei.getFileName());
		assertTrue(datei.getSize() > 500, "Verdaechtig kleine Datei: " + datei.getSize() + " Bytes");
	}

	@Test
	@Order(5)
	@DisplayName("Loeschen entfernt den Kanal, das Ticket bleibt als Datensatz erhalten")
	void loeschen() {
		final Panel panel = PanelDao.byId(panelId).orElseThrow();
		final Ticket ticket = TicketDao.byChannel(kanalId).orElseThrow();

		TicketService.delete(guild, ticket, panel, guild.getSelfMember().getId());

		assertTrue(kanalIstWeg(kanalId), "Der Kanal steht noch auf dem Server");

		// Der Kanal ist weg — der Nachweis, dass es ihn gab, muss bleiben.
		final var loeschEintrag = logEintragMitTitel("Ticket gelöscht");
		assertNotNull(loeschEintrag, "Im Log-Kanal fehlt der Lösch-Eintrag");
		assertEquals(1, loeschEintrag.getAttachments().size(),
				"Auch beim Löschen muss der Verlauf als Datei mitgehen");
		assertTrue(loeschEintrag.getAttachments().get(0).getFileName().endsWith(".html"));

		// Entscheidend: die Zeile bleibt. Ein geloeschtes Ticket ist nicht
		// vergessen — Nummer, Eroeffner und Verlauf sind weiter abrufbar,
		// gerade weil der Kanal weg ist.
		final Ticket nachher = TicketDao.byId(ticket.id()).orElseThrow(
				() -> new AssertionError("Der Datensatz wurde mitgeloescht"));
		assertEquals(Ticket.Status.DELETED, nachher.status());
		assertEquals(ticket.number(), nachher.number());
		assertEquals(ticket.ownerId(), nachher.ownerId());

		kanalId = null;   // tearDown muss ihn nicht mehr aufraeumen
	}

	/** Wartet auf einen Log-Eintrag mit diesem Titel. postLog schickt asynchron. */
	private net.dv8tion.jda.api.entities.Message logEintragMitTitel(String titel) {
		for (int versuch = 0; versuch < 20; versuch++) {
			final var treffer = logKanal.getHistory().retrievePast(10).complete().stream()
					.filter(m -> m.getEmbeds().stream()
							.anyMatch(e -> titel.equals(e.getTitle())))
					.findFirst();
			if (treffer.isPresent()) {
				return treffer.get();
			}
			try {
				Thread.sleep(500);
			} catch (final InterruptedException e) {
				Thread.currentThread().interrupt();
				return null;
			}
		}
		return null;
	}

	/** Discord bestaetigt das Loeschen, der Cache zieht ueber das Gateway nach. */
	private boolean kanalIstWeg(String id) {
		for (int versuch = 0; versuch < 20; versuch++) {
			if (guild.getTextChannelById(id) == null) {
				return true;
			}
			try {
				Thread.sleep(250);
			} catch (final InterruptedException e) {
				Thread.currentThread().interrupt();
				return false;
			}
		}
		return false;
	}

	@Test
	@Order(6)
	@DisplayName("Nach dem Schliessen ist wieder ein Ticket moeglich")
	void nachSchliessenWiederMoeglich() {
		final Panel panel = PanelDao.byId(panelId).orElseThrow();
		final TicketService.OpenResult result = TicketService.open(guild, panel, guild.getSelfMember());
		assertTrue(result.ok(), () -> "Das Limit haengt: " + result.error());

		// Aufraeumen: dieser Kanal gehoert nicht zum Rest des Tests.
		final Optional<Ticket> neu = TicketDao.byChannel(result.channel().getId());
		result.channel().delete().complete();
		neu.ifPresent(t -> Database.update("DELETE FROM tickets WHERE id = ?", t.id()));
	}
}
