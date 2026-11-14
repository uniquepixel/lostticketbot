package api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Die Dashboard-API muss auf JEDE Anfrage antworten.
 *
 * Der Anlass ist ein echter Ausfall vom 09.09.2026: Aufrufe auf
 * {@code /api/stats}, {@code /api/panels} und {@code /api/tickets} liefen ins
 * Timeout, ohne dass irgendetwas im Log stand. Ursache war ein Fehler, den
 * {@code catch (Exception)} nicht faengt — ein {@link Error} beendet den
 * Arbeitsthread des HttpServers, und der Aufrufer wartet auf eine Antwort, die
 * nie kommt. Von aussen sah das aus wie ein haengender Server.
 *
 * Jeder Test hier setzt deshalb einen Timeout. Ein haengender Endpunkt soll
 * einen ROTEN Test ergeben, nicht einen ewig laufenden.
 *
 * Ohne Discord und ohne Datenbank: geprueft wird die Zustellung, nicht der
 * Inhalt. Genau dort lag der Fehler.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TicketApiServerTest {

	private static final Duration GEDULD = Duration.ofSeconds(5);

	private TicketApiServer server;
	private int port;
	private HttpClient client;

	@BeforeAll
	void setUp() throws Exception {
		port = freierPort();
		server = new TicketApiServer(port, "geheim");
		server.start();

		// Zwei Endpunkte, die absichtlich scheitern — einer mit einer
		// Ausnahme, einer mit einem Error. Der zweite ist der eigentliche
		// Anlass dieses Tests.
		server.registriere("/api/kaputt-exception", anfrage -> {
			throw new IllegalStateException("absichtlich");
		});
		server.registriere("/api/kaputt-error", anfrage -> {
			throw new NoClassDefFoundError("absichtlich");
		});

		// Ein schreibender Endpunkt, der zurueckgibt, was bei ihm ankam.
		server.registriere("/api/echo", anfrage -> java.util.Map.of(
				"methode", anfrage.methode(),
				"koerper", anfrage.koerper(),
				"admin", anfrage.websiteAdmin()), true);
		server.registriere("/api/verboten", anfrage -> {
			throw new verwaltung.Verwaltung.KeinRecht("nein");
		}, true);

		client = HttpClient.newBuilder().connectTimeout(GEDULD).build();
	}

	private HttpResponse<String> post(String pfad, String koerper, String... kopfzeilen) throws Exception {
		final HttpRequest.Builder b = HttpRequest.newBuilder()
				.uri(URI.create("http://127.0.0.1:" + port + pfad))
				.timeout(GEDULD)
				.header("Authorization", "Bearer geheim").header("X-Discord-User", "1")
				.POST(HttpRequest.BodyPublishers.ofString(koerper));
		for (int i = 0; i + 1 < kopfzeilen.length; i += 2) {
			b.setHeader(kopfzeilen[i], kopfzeilen[i + 1]);
		}
		return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
	}

	@Test
	@DisplayName("Schreibender Pfad: POST-Koerper kommt als JSON-Objekt an, Admin nur mit Kopfzeile")
	void postKoerper() throws Exception {
		HttpResponse<String> r = post("/api/echo", "{\"guild\":\"5\",\"n\":3}");
		assertEquals(200, r.statusCode(), r.body());
		assertTrue(r.body().contains("\"guild\":\"5\""));
		assertTrue(r.body().contains("\"admin\":false"));
		r = post("/api/echo", "{}", "X-Website-Admin", "1");
		assertTrue(r.body().contains("\"admin\":true"));
		r = post("/api/echo", "{}", "X-Website-Admin", "true");
		assertTrue(r.body().contains("\"admin\":false"), "nur genau \"1\" zaehlt");
	}

	@Test
	@DisplayName("Schreibender Pfad: kaputtes JSON 400, zu gross 413, ohne Token 401, kein Recht 403")
	void postGrenzen() throws Exception {
		assertEquals(400, post("/api/echo", "[1,2]").statusCode());
		assertEquals(400, post("/api/echo", "kein json").statusCode());
		assertEquals(413, post("/api/echo", "{\"x\":\"" + "a".repeat(70_000) + "\"}").statusCode());
		assertEquals(401, post("/api/echo", "{}", "Authorization", "Bearer falsch").statusCode());
		final HttpResponse<String> r = post("/api/verboten", "{}");
		assertEquals(403, r.statusCode());
		assertTrue(r.body().contains("nein"));
	}

	@AfterAll
	void tearDown() {
		if (server != null) {
			server.stop();
		}
	}

	private static int freierPort() throws Exception {
		try (ServerSocket s = new ServerSocket(0)) {
			return s.getLocalPort();
		}
	}

	private HttpResponse<String> get(String pfad, String... kopfzeilen) throws Exception {
		final HttpRequest.Builder b = HttpRequest.newBuilder()
				.uri(URI.create("http://127.0.0.1:" + port + pfad))
				.timeout(GEDULD)
				.GET();
		for (int i = 0; i + 1 < kopfzeilen.length; i += 2) {
			b.header(kopfzeilen[i], kopfzeilen[i + 1]);
		}
		return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
	}

	// -----------------------------------------------------------------------

	@Test
	@DisplayName("Der Gesundheitscheck antwortet ohne alles")
	void health() throws Exception {
		final HttpResponse<String> r = get("/api/health");
		assertEquals(200, r.statusCode());
		assertTrue(r.body().contains("ok"));
	}

	@Test
	@DisplayName("Falscher Token: 401 statt Schweigen")
	void falscherToken() throws Exception {
		final HttpResponse<String> r = get("/api/stats",
				"Authorization", "Bearer falsch", "X-Discord-User", "1");
		assertEquals(401, r.statusCode());
	}

	@Test
	@DisplayName("Ohne Token: 401 statt Schweigen")
	void ohneToken() throws Exception {
		assertEquals(401, get("/api/stats").statusCode());
	}

	@Test
	@DisplayName("Ohne X-Discord-User: 400, denn ungefiltert wird nichts ausgeliefert")
	void ohneNutzer() throws Exception {
		final HttpResponse<String> r = get("/api/stats", "Authorization", "Bearer geheim");
		assertEquals(400, r.statusCode());
		assertTrue(r.body().contains("X-Discord-User"));
	}

	@Test
	@DisplayName("Jeder Endpunkt antwortet — auch wenn im Inneren etwas scheitert")
	void alleEndpunkteAntworten() throws Exception {
		// Ohne laufendes JDA scheitert jeder dieser Endpunkte im Inneren. Das
		// ist der Punkt: er muss trotzdem eine Antwort schicken.
		for (final String pfad : new String[] { "/api/stats", "/api/panels", "/api/tickets",
				"/api/legacy", "/api/guilds" }) {
			final HttpResponse<String> r = get(pfad + "?guild=1",
					"Authorization", "Bearer geheim", "X-Discord-User", "1");
			assertTrue(r.statusCode() >= 200 && r.statusCode() < 600,
					pfad + " hat keinen brauchbaren Status geliefert");
			assertTrue(r.body() != null && !r.body().isBlank(),
					pfad + " hat einen leeren Rumpf geliefert");
		}
	}

	@Test
	@DisplayName("Ein Error im Endpunkt endet als 500, nicht als haengende Verbindung")
	void errorHaengtNicht() throws Exception {
		// Vor dem Fix vom 09.09.2026 lief dieser Aufruf ins Timeout: der
		// Arbeitsthread starb am Error, und niemand schickte eine Antwort.
		final HttpResponse<String> r = get("/api/kaputt-error",
				"Authorization", "Bearer geheim", "X-Discord-User", "1");
		assertEquals(500, r.statusCode());
		assertTrue(r.body().contains("Fehler"));
	}

	@Test
	@DisplayName("Eine Ausnahme im Endpunkt endet ebenfalls als 500")
	void exceptionHaengtNicht() throws Exception {
		final HttpResponse<String> r = get("/api/kaputt-exception",
				"Authorization", "Bearer geheim", "X-Discord-User", "1");
		assertEquals(500, r.statusCode());
	}

	@Test
	@DisplayName("Nach einem Error bedient der Server weiter")
	void serverLebtWeiter() throws Exception {
		get("/api/kaputt-error", "Authorization", "Bearer geheim", "X-Discord-User", "1");
		get("/api/kaputt-error", "Authorization", "Bearer geheim", "X-Discord-User", "1");
		// Vier Fehlschlaege haetten frueher alle vier Arbeitsthreads getoetet.
		get("/api/kaputt-error", "Authorization", "Bearer geheim", "X-Discord-User", "1");
		get("/api/kaputt-error", "Authorization", "Bearer geheim", "X-Discord-User", "1");
		assertEquals(200, get("/api/health").statusCode(),
				"Der Server muss nach Fehlern weiter antworten");
	}

	@Test
	@DisplayName("Unbekannter Pfad: 404")
	void unbekannt() throws Exception {
		assertEquals(404, get("/api/gibtsnicht").statusCode());
	}

	@Test
	@DisplayName("Andere Methoden als GET werden abgewiesen")
	void nurGet() throws Exception {
		final HttpRequest r = HttpRequest.newBuilder()
				.uri(URI.create("http://127.0.0.1:" + port + "/api/stats"))
				.timeout(GEDULD)
				.POST(HttpRequest.BodyPublishers.noBody())
				.build();
		assertEquals(405, client.send(r, HttpResponse.BodyHandlers.ofString()).statusCode());
	}
}
