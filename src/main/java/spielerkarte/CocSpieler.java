package spielerkarte;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Holt ein Spielerprofil aus der CoC-API.
 *
 * Adresse und Schlüssel kommen aus derselben Umschalter-Datei wie beim LOST
 * Manager ({@code ~/.config/lost-umzug/umschalter/coc.conf}, Zeilen {@code url=}
 * und {@code key=}) und werden bei jedem Aufruf frisch gelesen — schaltet
 * {@code api-umschalten.sh} zwischen Proxy und direktem Key um, folgt die
 * Spielerkarte ohne Neustart. Der Proxy verlangt einen Browser-User-Agent,
 * sonst antwortet Cloudflare mit 403/1010.
 */
public final class CocSpieler {

	static final String STANDARD_DATEI = System.getProperty("user.home")
			+ "/.config/lost-umzug/umschalter/coc.conf";
	private static final ObjectMapper JSON = new ObjectMapper();
	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10)).build();

	private CocSpieler() {
	}

	/** Ergebnis: Profil, oder ein kurzer Grund, warum keins kam. */
	public record Antwort(JsonNode profil, int status, String grund) {
		public boolean ok() {
			return profil != null;
		}
	}

	public static Antwort hole(String tag) {
		final String datei = System.getenv().getOrDefault("TICKETBOT_COC_CONF", STANDARD_DATEI);
		final String url;
		final String key;
		try {
			final String inhalt = Files.readString(Path.of(datei));
			url = wert(inhalt, "url").orElse("");
			key = wert(inhalt, "key").orElse("");
		} catch (final IOException e) {
			return new Antwort(null, 0, "Umschalter-Datei fehlt");
		}
		if (url.isBlank() || key.isBlank()) {
			return new Antwort(null, 0, "Umschalter-Datei ohne url/key");
		}
		final HttpRequest anfrage = HttpRequest.newBuilder(URI.create(url.replaceAll("/+$", "") + "/players/%23" + tag))
				.timeout(Duration.ofSeconds(15))
				.header("Authorization", "Bearer " + key)
				.header("Accept", "application/json")
				.header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) LOST-Ticketbot")
				.GET().build();
		try {
			final HttpResponse<String> antwort = HTTP.send(anfrage, HttpResponse.BodyHandlers.ofString());
			if (antwort.statusCode() == 200) {
				return new Antwort(JSON.readTree(antwort.body()), 200, null);
			}
			return new Antwort(null, antwort.statusCode(),
					antwort.statusCode() == 404 ? "Tag gibt es nicht"
							: antwort.body().contains("inMaintenance") ? "CoC-API in Wartung"
							: "HTTP " + antwort.statusCode());
		} catch (final IOException e) {
			return new Antwort(null, 0, e.getClass().getSimpleName());
		} catch (final InterruptedException e) {
			Thread.currentThread().interrupt();
			return new Antwort(null, 0, "unterbrochen");
		}
	}

	static Optional<String> wert(String inhalt, String name) {
		for (final String zeile : inhalt.split("\\R")) {
			if (zeile.startsWith(name + "=")) {
				return Optional.of(zeile.substring(name.length() + 1).trim());
			}
		}
		return Optional.empty();
	}
}
