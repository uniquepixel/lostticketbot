package transcript;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Uhrzeiten im Transcript. Braucht weder Datenbank noch Discord.
 *
 * Bis zum 20.11.2026 standen die Zeiten so in der Datei, wie sie in der
 * Datenbank liegen (UTC), ohne Hinweis — fuer das Team eine bzw. zwei Stunden
 * zu frueh.
 */
class HtmlRendererZeitTest {

	@Test
	@DisplayName("Winter: UTC wird zu deutscher Zeit (+1)")
	void winter() {
		assertEquals("19.11.2026 17:20",
				HtmlRenderer.zeit(LocalDateTime.of(2026, 11, 19, 16, 20, 28), ZoneOffset.UTC));
	}

	@Test
	@DisplayName("Sommer: +2, auch ueber Mitternacht in den naechsten Tag")
	void sommer() {
		assertEquals("02.07.2026 00:30",
				HtmlRenderer.zeit(LocalDateTime.of(2026, 7, 1, 22, 30), ZoneOffset.UTC));
	}

	@Test
	@DisplayName("Laeuft der Bot schon in deutscher Zeit, aendert sich nichts")
	void schonDeutsch() {
		assertEquals("19.11.2026 16:20",
				HtmlRenderer.zeit(LocalDateTime.of(2026, 11, 19, 16, 20), ZoneId.of("Europe/Berlin")));
	}

	@Test
	@DisplayName("Fehlende Zeit bleibt ein Strich")
	void leer() {
		assertEquals("—", HtmlRenderer.zeit(null, ZoneOffset.UTC));
	}
}
