package api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import db.Database;
import db.FrageDao;
import db.MenuDao;
import db.PanelDao;
import lostticketbot.Bot;
import model.Menu;
import model.Panel;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.interactions.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.requests.GatewayIntent;

/**
 * Die Ticket-Verwaltung der Website, Ende zu Ende gegen den TESTSERVER.
 *
 * Echte HTTP-Aufrufe an die echte {@link TicketApiServer}-Instanz, echte
 * Datenbank, echtes Discord: Panel anlegen und einstellen, Rollen, Formular,
 * Menü anlegen, posten, abgleichen, umziehen, löschen — und dass jemand ohne
 * „Server verwalten“ nichts davon darf.
 *
 * Handelnder ist der Bot selbst (er hat Administrator). Als Unbefugter dient
 * eine ID, die es nicht gibt. Läuft nur mit TICKETBOT_TOKEN,
 * TICKETBOT_TEST_GUILD und Datenbank; legt Kategorie, zwei Kanäle und eine
 * Rolle an und räumt alles wieder weg. Nie gegen einen Produktivserver.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class VerwaltungEndToEndTest {

	private static final String TOKEN = "vt-geheim";
	private static final String FREMDER = "100000000000000001";
	private static final Duration GEDULD = Duration.ofSeconds(20);
	private static final List<String> PRODUKTIV = List.of("733857906117574717", "1108449987827876022");

	private final ObjectMapper json = new ObjectMapper();
	private boolean verfuegbar;
	private JDA jda;
	private Guild guild;
	private Category kategorie;
	private TextChannel kanalA;
	private TextChannel kanalB;
	private Role rolle;
	private TicketApiServer server;
	private HttpClient client;
	private int port;
	private String ich;

	private long panelId;
	private long menuId;
	private String nachrichtId;

	@BeforeAll
	void setUp() throws Exception {
		final String token = System.getenv("TICKETBOT_TOKEN");
		final String guildId = System.getenv("TICKETBOT_TEST_GUILD");
		final String dbUrl = System.getenv("TICKETBOT_DB_URL");
		verfuegbar = token != null && !token.isBlank() && guildId != null && !guildId.isBlank()
				&& dbUrl != null && !dbUrl.isBlank();
		assumeTrue(verfuegbar, "TICKETBOT_TOKEN/TEST_GUILD/DB_URL fehlen — Verwaltungs-E2E übersprungen");
		assumeTrue(!PRODUKTIV.contains(guildId), "Nie gegen einen Produktivserver");

		Database.init(dbUrl, System.getenv("TICKETBOT_DB_USER"),
				System.getenv().getOrDefault("TICKETBOT_DB_PASSWORD", ""));
		Database.applySchema();

		jda = JDABuilder.createDefault(token).enableIntents(GatewayIntent.GUILD_MEMBERS).build().awaitReady();
		Bot.setJdaFuerTests(jda);
		guild = jda.getGuildById(guildId);
		assertNotNull(guild, "Der Bot ist nicht auf dem Testserver");
		ich = jda.getSelfUser().getId();

		kategorie = guild.createCategory("vt-verwaltung").complete();
		kanalA = guild.createTextChannel("vt-menue-a").setParent(kategorie).complete();
		kanalB = guild.createTextChannel("vt-menue-b").setParent(kategorie).complete();
		rolle = guild.createRole().setName("vt-support").complete();

		try (ServerSocket s = new ServerSocket(0)) {
			port = s.getLocalPort();
		}
		server = new TicketApiServer(port, TOKEN);
		server.start();
		client = HttpClient.newBuilder().connectTimeout(GEDULD).build();
	}

	@AfterAll
	void tearDown() {
		if (!verfuegbar) {
			return;
		}
		if (server != null) {
			server.stop();
		}
		if (menuId > 0) {
			Database.update("DELETE FROM menus WHERE id = ?", menuId);
		}
		if (panelId > 0) {
			Database.update("DELETE FROM panels WHERE id = ?", panelId);
		}
		for (final var k : new TextChannel[] { kanalA, kanalB }) {
			if (k != null) {
				k.delete().complete();
			}
		}
		if (kategorie != null) {
			kategorie.delete().complete();
		}
		if (rolle != null) {
			rolle.delete().complete();
		}
		Bot.setJdaFuerTests(null);
		if (jda != null) {
			jda.shutdown();
		}
	}

	// -----------------------------------------------------------------------

	private HttpResponse<String> get(String pfad, String wer, boolean admin) throws Exception {
		final HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + pfad))
				.timeout(GEDULD).header("Authorization", "Bearer " + TOKEN).header("X-Discord-User", wer).GET();
		if (admin) {
			b.header("X-Website-Admin", "1");
		}
		return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
	}

	private HttpResponse<String> post(String aktion, String wer, Map<String, Object> koerper) throws Exception {
		final Map<String, Object> mitGuild = new java.util.HashMap<>(koerper);
		mitGuild.put("guild", guild.getId());
		return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/verwaltung/" + aktion))
				.timeout(GEDULD).header("Authorization", "Bearer " + TOKEN).header("X-Discord-User", wer)
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(mitGuild))).build(),
				HttpResponse.BodyHandlers.ofString());
	}

	private JsonNode ok(HttpResponse<String> r) throws Exception {
		assertEquals(200, r.statusCode(), r.body());
		return json.readTree(r.body());
	}

	private void abgelehnt(int code, HttpResponse<String> r, String imText) throws Exception {
		assertEquals(code, r.statusCode(), r.body());
		if (imText != null) {
			assertTrue(json.readTree(r.body()).path("error").asText().contains(imText), r.body());
		}
	}

	private Message nachricht(TextChannel k, String id) {
		return k.retrieveMessageById(id).complete();
	}

	// -----------------------------------------------------------------------

	@Test
	@Order(1)
	@DisplayName("Rechte: nur wer „Server verwalten“ hat oder Website-Admin ist")
	void rechte() throws Exception {
		final JsonNode meine = ok(get("/api/verwaltung/guilds", ich, false));
		assertTrue(meine.path("items").toString().contains(guild.getId()));

		assertEquals(0, ok(get("/api/verwaltung/guilds", FREMDER, false)).path("total").asInt());
		assertTrue(ok(get("/api/verwaltung/guilds", FREMDER, true)).path("items").toString().contains(guild.getId()));

		abgelehnt(403, get("/api/verwaltung?guild=" + guild.getId(), FREMDER, false), "Server verwalten");
		abgelehnt(403, post("panel/erstellen", FREMDER, Map.of("name", "vt-darf-nicht")), null);
		assertTrue(PanelDao.byName(guild.getId(), "vt-darf-nicht").isEmpty());
	}

	@Test
	@Order(2)
	@DisplayName("Panel anlegen; doppelter Name abgewiesen")
	void panelAnlegen() throws Exception {
		final String name = "vt-" + System.currentTimeMillis();
		ok(post("panel/erstellen", ich, Map.of("name", name)));
		panelId = PanelDao.byName(guild.getId(), name).orElseThrow().id();
		abgelehnt(400, post("panel/erstellen", ich, Map.of("name", name)), "gibt es schon");
	}

	@Test
	@Order(3)
	@DisplayName("Panel einstellen: mehrere Felder auf einmal, bei einem Fehler gar keins")
	void panelEinstellen() throws Exception {
		ok(post("panel/setzen", ich, Map.of("panel", panelId, "felder", Map.of(
				"NAME_OFFEN", "vt-{count}", "LOG_KANAL", kanalA.getId(), "KATEGORIE_OFFEN", kategorie.getId(),
				"STELLEN", 3, "DM_BEIM_OEFFNEN", true, "WILLKOMMENSTEXT", "Hallo {user}"))));
		Panel p = PanelDao.byId(panelId).orElseThrow();
		assertEquals("vt-{count}", p.namePatternOpen());
		assertEquals(kanalA.getId(), p.logChannelId());
		assertEquals(kategorie.getId(), p.categoryOpened());
		assertEquals(3, p.counterPadding());
		assertTrue(p.dmOnOpen());

		abgelehnt(400, post("panel/setzen", ich, Map.of("panel", panelId, "felder", Map.of(
				"ZAEHLER", 77, "STELLEN", 99))), "Stellenzahl");
		abgelehnt(400, post("panel/setzen", ich, Map.of("panel", panelId, "felder", Map.of(
				"LOG_KANAL", kategorie.getId()))), "Textkanal");
		p = PanelDao.byId(panelId).orElseThrow();
		assertEquals(0, p.counter(), "Halb geschrieben: der Zähler darf sich nicht geändert haben");
		assertEquals(kanalA.getId(), p.logChannelId());

		// Textfeld leeren
		ok(post("panel/setzen", ich, Map.of("panel", panelId, "felder", Map.of("WILLKOMMENSTEXT", ""))));
		assertNull(PanelDao.byId(panelId).orElseThrow().welcomeText());
	}

	@Test
	@Order(4)
	@DisplayName("Support- und Ping-Rollen; @everyone geht nicht")
	void rollen() throws Exception {
		ok(post("panel/rolle", ich, Map.of("panel", panelId, "rolle", rolle.getId(), "art", "support")));
		ok(post("panel/rolle", ich, Map.of("panel", panelId, "rolle", rolle.getId(), "art", "ping")));
		assertEquals(List.of(rolle.getId()), PanelDao.supportRoles(panelId));
		assertEquals(List.of(rolle.getId()), PanelDao.pingRoles(panelId));
		abgelehnt(400, post("panel/rolle", ich, Map.of("panel", panelId, "rolle", guild.getPublicRole().getId())),
				"@everyone");
		ok(post("panel/rolle", ich, Map.of("panel", panelId, "rolle", rolle.getId(), "art", "ping", "entfernen", true)));
		assertTrue(PanelDao.pingRoles(panelId).isEmpty());
	}

	@Test
	@Order(5)
	@DisplayName("Formularfragen speichern, in der Übersicht sehen, wieder entfernen")
	void fragen() throws Exception {
		ok(post("panel/fragen", ich, Map.of("panel", panelId, "fragen", List.of(
				Map.of("label", "Spielername", "placeholder", "z. B. Simon", "pflicht", true),
				Map.of("label", "Warum LOST?", "lang", true, "pflicht", false, "max", 1000)))));
		assertEquals(2, FrageDao.vonPanel(panelId).size());
		final JsonNode u = ok(get("/api/verwaltung?guild=" + guild.getId(), ich, false));
		JsonNode meins = null;
		for (final JsonNode p : u.path("panels")) {
			if (p.path("id").asLong() == panelId) {
				meins = p;
			}
		}
		assertNotNull(meins);
		assertEquals("Warum LOST?", meins.path("fragen").get(1).path("label").asText());
		assertEquals(1000, meins.path("fragen").get(1).path("max").asInt());
		assertTrue(u.path("rollen").toString().contains(rolle.getId()));
		assertTrue(u.path("kanaele").toString().contains(kanalA.getId()));
		assertTrue(u.path("kategorien").toString().contains(kategorie.getId()));

		ok(post("panel/fragen", ich, Map.of("panel", panelId, "fragen", List.of())));
		assertTrue(FrageDao.vonPanel(panelId).isEmpty());
	}

	@Test
	@Order(6)
	@DisplayName("Menü anlegen, Eintrag, posten — die Nachricht steht mit dem Knopf in Discord")
	void menuPosten() throws Exception {
		ok(post("menu/erstellen", ich, Map.of("kanal", kanalA.getId(), "stil", "knoepfe")));
		menuId = MenuDao.byGuild(guild.getId()).stream().filter(m -> m.channelId().equals(kanalA.getId()))
				.findFirst().orElseThrow().id();

		abgelehnt(400, post("menu/posten", ich, Map.of("menu", menuId)), "kein Panel");
		ok(post("menu/text", ich, Map.of("menu", menuId, "titel", "Verwaltungstest", "text", "Zeile 1\nZeile 2",
				"farbe", "#1ec45c")));
		abgelehnt(400, post("menu/eintrag", ich, Map.of("menu", menuId, "panel", panelId, "emoji", "ticket")), "Emoji");
		ok(post("menu/eintrag", ich, Map.of("menu", menuId, "panel", panelId, "label", "Test öffnen", "emoji", "🎫",
				"knopfstil", 3)));

		final JsonNode r = ok(post("menu/posten", ich, Map.of("menu", menuId)));
		assertTrue(r.path("meldung").asText().contains("gepostet"));
		nachrichtId = MenuDao.byId(menuId).orElseThrow().messageId();
		assertNotNull(nachrichtId);
		final Message m = nachricht(kanalA, nachrichtId);
		assertEquals("Verwaltungstest", m.getEmbeds().get(0).getTitle());
		final Button b = m.getButtons().get(0);
		assertEquals("Test öffnen", b.getLabel());
		assertEquals("tb:open:" + panelId, b.getId());
		assertEquals(0x1ec45c, m.getEmbeds().get(0).getColorRaw());
	}

	@Test
	@Order(7)
	@DisplayName("Abgleichen ändert dieselbe Nachricht, statt eine zweite zu posten")
	void abgleichen() throws Exception {
		ok(post("menu/eintrag", ich, Map.of("menu", menuId, "panel", panelId, "label", "Neu beschriftet")));
		final JsonNode r = ok(post("menu/posten", ich, Map.of("menu", menuId)));
		assertTrue(r.path("meldung").asText().contains("abgeglichen"));
		assertEquals(nachrichtId, MenuDao.byId(menuId).orElseThrow().messageId());
		assertEquals("Neu beschriftet", nachricht(kanalA, nachrichtId).getButtons().get(0).getLabel());

		ok(post("menu/stil", ich, Map.of("menu", menuId, "stil", "dropdown")));
		ok(post("menus/abgleichen", ich, Map.of()));
		final Message m = nachricht(kanalA, nachrichtId);
		assertTrue(m.getActionRows().get(0).getComponents().get(0) instanceof StringSelectMenu);
		assertEquals(1, kanalA.getHistory().retrievePast(10).complete().size(), "keine zweite Nachricht");
	}

	@Test
	@Order(8)
	@DisplayName("Ein deaktiviertes Panel verschwindet beim Abgleichen aus dem Menü")
	void deaktivieren() throws Exception {
		ok(post("panel/setzen", ich, Map.of("panel", panelId, "felder", Map.of("AKTIV", false))));
		ok(post("menu/posten", ich, Map.of("menu", menuId)));
		assertTrue(nachricht(kanalA, nachrichtId).getActionRows().isEmpty());
		ok(post("panel/setzen", ich, Map.of("panel", panelId, "felder", Map.of("AKTIV", true))));
		ok(post("menu/posten", ich, Map.of("menu", menuId)));
		assertFalse(nachricht(kanalA, nachrichtId).getActionRows().isEmpty());
	}

	@Test
	@Order(9)
	@DisplayName("Umziehen löscht die alte Nachricht; neu posten im neuen Kanal")
	void umziehen() throws Exception {
		ok(post("menu/kanal", ich, Map.of("menu", menuId, "kanal", kanalB.getId())));
		final Menu m = MenuDao.byId(menuId).orElseThrow();
		assertEquals(kanalB.getId(), m.channelId());
		assertFalse(m.isPosted());
		assertGeloescht(kanalA, nachrichtId);

		ok(post("menu/posten", ich, Map.of("menu", menuId)));
		nachrichtId = MenuDao.byId(menuId).orElseThrow().messageId();
		assertNotNull(nachricht(kanalB, nachrichtId));
	}

	@Test
	@Order(10)
	@DisplayName("Ein Unbefugter kann das Menü nicht löschen; der Befugte schon, samt Nachricht")
	void loeschen() throws Exception {
		abgelehnt(403, post("menu/loeschen", FREMDER, Map.of("menu", menuId)), null);
		assertTrue(MenuDao.byId(menuId).isPresent());

		ok(post("menu/loeschen", ich, Map.of("menu", menuId)));
		assertTrue(MenuDao.byId(menuId).isEmpty());
		assertGeloescht(kanalB, nachrichtId);
		menuId = 0;
	}

	@Test
	@Order(11)
	@DisplayName("Website-Admin ohne Discord-Recht darf; fremde Aktionen gibt es nicht")
	void websiteAdmin() throws Exception {
		final HttpResponse<String> r = client.send(HttpRequest
				.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/verwaltung/config/limit"))
				.timeout(GEDULD).header("Authorization", "Bearer " + TOKEN).header("X-Discord-User", FREMDER)
				.header("X-Website-Admin", "1").header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(
						"{\"guild\":\"" + guild.getId() + "\",\"anzahl\":0}"))
				.build(), HttpResponse.BodyHandlers.ofString());
		ok(r);
		abgelehnt(400, post("panel/loeschen", ich, Map.of("panel", panelId)), "Unbekannte Aktion");
	}

	private void assertGeloescht(TextChannel k, String id) {
		try {
			k.retrieveMessageById(id).complete();
			throw new AssertionError("Nachricht " + id + " steht noch in #" + k.getName());
		} catch (final ErrorResponseException e) {
			assertEquals(10008, e.getErrorCode());
		}
	}
}
