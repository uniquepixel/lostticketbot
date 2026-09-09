package api;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * REST-Schnittstelle fuer das Dashboard auf der Website.
 *
 * Gebaut wie der RestApiServer des lostcrmanager: HttpServer aus dem JDK,
 * Token im Authorization-Header, ein Kontext je Pfad. Die Website spricht als
 * vertrauenswuerdiger Server mit uns und reicht in {@code X-Discord-User} die
 * ID desjenigen durch, der gerade eingeloggt ist.
 *
 * ENTSCHEIDEND FUER DEN DATENSCHUTZ: Ueber die Sichtbarkeit entscheidet dieser
 * Bot, nicht die Website. Er prueft die echten Discord-Rollen des Anfragenden
 * gegen die Support-Rollen des jeweiligen Panels. Das Dashboard zeigt damit
 * genau das, was derjenige in Discord ohnehin sehen koennte — und die
 * Orga-Tickets, in denen sich Mitglieder ueber Anfuehrer beschweren, bleiben
 * bei der Orga, auch wenn die Website spaeter einmal grosszuegiger wird.
 */
public class TicketApiServer {

	private final int port;
	private final String apiToken;
	private final ObjectMapper json = new ObjectMapper();
	private HttpServer server;

	public TicketApiServer(int port, String apiToken) {
		this.port = port;
		this.apiToken = apiToken;
	}

	public void start() throws IOException {
		server = HttpServer.create(new InetSocketAddress(port), 0);
		registriere("/api/tickets", TicketEndpoints::tickets);
		registriere("/api/panels", TicketEndpoints::panels);
		registriere("/api/guilds", TicketEndpoints::guilds);
		registriere("/api/stats", TicketEndpoints::stats);
		registriere("/api/legacy", TicketEndpoints::legacy);
		server.createContext("/api/health", exchange -> {
			antworten(exchange, 200, Map.of("status", "ok"));
		});
		// Acht statt vier: eine einzige Dashboard-Seite feuert vier Abfragen
		// parallel, und die Sichtbarkeitspruefung fragt notfalls blockierend bei
		// Discord nach. Mit vier Threads blockiert ein zweiter Betrachter den
		// ersten.
		server.setExecutor(Executors.newFixedThreadPool(8));
		server.start();
		System.out.println("Dashboard-API laeuft auf Port " + port);
	}

	/**
	 * Haengt einen Endpunkt an einen Pfad.
	 *
	 * Sichtbar fuer den Test, damit der einen Endpunkt einhaengen kann, der
	 * absichtlich scheitert — nur so laesst sich pruefen, dass auch dann eine
	 * Antwort rausgeht statt einer haengenden Verbindung.
	 */
	void registriere(String pfad, Endpunkt endpunkt) {
		server.createContext(pfad, exchange -> route(exchange, endpunkt));
	}

	public void stop() {
		if (server != null) {
			server.stop(0);
		}
	}

	@FunctionalInterface
	interface Endpunkt {
		Object bearbeiten(Anfrage anfrage) throws Exception;
	}

	/** Was ein Endpunkt ueber die Anfrage wissen muss. */
	public record Anfrage(String pfad, Map<String, String> parameter, String discordUserId) {

		public String param(String name) {
			return parameter.get(name);
		}

		public String param(String name, String standard) {
			final String wert = parameter.get(name);
			return wert == null || wert.isBlank() ? standard : wert;
		}

		public int intParam(String name, int standard) {
			try {
				return Integer.parseInt(param(name, String.valueOf(standard)));
			} catch (final NumberFormatException e) {
				return standard;
			}
		}
	}

	private void route(HttpExchange exchange, Endpunkt endpunkt) throws IOException {
		try {
			if (!"GET".equals(exchange.getRequestMethod())) {
				antworten(exchange, 405, Map.of("error", "Nur GET"));
				return;
			}
			if (!tokenStimmt(exchange)) {
				antworten(exchange, 401, Map.of("error", "Token fehlt oder ist falsch"));
				return;
			}

			final String discordUser = exchange.getRequestHeaders().getFirst("X-Discord-User");
			if (discordUser == null || discordUser.isBlank()) {
				// Ohne zu wissen, WER fragt, koennen wir nicht filtern - und
				// ungefiltert auszuliefern waere genau der Fehler, den dieser
				// Server verhindern soll.
				antworten(exchange, 400, Map.of("error", "X-Discord-User fehlt"));
				return;
			}

			final Anfrage anfrage = new Anfrage(
					exchange.getRequestURI().getPath(),
					parameterLesen(exchange.getRequestURI().getRawQuery()),
					discordUser);

			antworten(exchange, 200, endpunkt.bearbeiten(anfrage));

		} catch (final IllegalArgumentException e) {
			antworten(exchange, 400, Map.of("error", e.getMessage()));
		} catch (final SecurityException e) {
			// Bewusst 404 statt 403: wer ein Ticket nicht sehen darf, soll auch
			// nicht erfahren, dass es existiert.
			antworten(exchange, 404, Map.of("error", "Nicht gefunden"));
		} catch (final Throwable e) {
			// Throwable, nicht Exception. Ein Error — etwa ein
			// NoClassDefFoundError aus einem unvollstaendigen Fatjar — beendet
			// sonst den Arbeitsthread, OHNE dass je eine Antwort rausgeht: der
			// Aufrufer haengt bis zu seinem eigenen Timeout und sieht keinen
			// Grund. Genau das ist am 09.09.2026 passiert und war von aussen
			// nicht von einem haengenden Server zu unterscheiden.
			System.err.println("Dashboard-API: " + exchange.getRequestURI() + " -> " + e);
			e.printStackTrace();
			try {
				antworten(exchange, 500, Map.of("error", "Interner Fehler"));
			} catch (final Throwable ignored) {
				exchange.close();
			}
		}
	}

	private boolean tokenStimmt(HttpExchange exchange) {
		if (apiToken == null || apiToken.isBlank()) {
			return true;   // ungesichert nur, wenn bewusst kein Token gesetzt ist
		}
		final String kopf = exchange.getRequestHeaders().getFirst("Authorization");
		return kopf != null && kopf.equals("Bearer " + apiToken);
	}

	private static Map<String, String> parameterLesen(String query) {
		final Map<String, String> map = new HashMap<>();
		if (query == null || query.isBlank()) {
			return map;
		}
		for (final String paar : query.split("&")) {
			final int gleich = paar.indexOf('=');
			if (gleich > 0) {
				map.put(java.net.URLDecoder.decode(paar.substring(0, gleich), StandardCharsets.UTF_8),
						java.net.URLDecoder.decode(paar.substring(gleich + 1), StandardCharsets.UTF_8));
			}
		}
		return map;
	}

	private void antworten(HttpExchange exchange, int status, Object koerper) throws IOException {
		final byte[] daten = json.writeValueAsBytes(koerper);
		exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
		exchange.sendResponseHeaders(status, daten.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(daten);
		}
	}

	/** Hilfsform fuer Listenantworten, damit das Frontend immer dieselbe Huelle bekommt. */
	public static Map<String, Object> liste(List<?> eintraege, int gesamt) {
		final Map<String, Object> map = new HashMap<>();
		map.put("items", eintraege);
		map.put("total", gesamt);
		return map;
	}
}
