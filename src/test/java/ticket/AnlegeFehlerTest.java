package ticket;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.requests.Response;

/**
 * Was der Klickende liest, wenn Discord den Ticketkanal verweigert.
 *
 * Anlass: Im Oktober 2026 stand LOST Family an Discords Grenze von 500 Kanälen.
 * Die Meldung lautete nur "Der Kanal konnte nicht angelegt werden: 30013 …",
 * und das Team musste raten, was gemeint war.
 */
class AnlegeFehlerTest {

	@Test
	@DisplayName("500-Kanäle-Grenze wird verständlich benannt")
	void kanalgrenze() {
		final String text = TicketService.fehlertextAnlegen(
				ErrorResponseException.create(ErrorResponse.MAX_CHANNELS, antwort()));
		assertTrue(text.contains("500 Kanälen"), text);
		assertTrue(text.contains("Orga"), text);
	}

	@Test
	@DisplayName("Andere Fehler behalten ihren Wortlaut")
	void andererFehler() {
		final String text = TicketService.fehlertextAnlegen(new IllegalStateException("kaputt"));
		assertTrue(text.startsWith("Der Kanal konnte nicht angelegt werden"), text);
		assertTrue(text.contains("kaputt"), text);
	}

	@Test
	@DisplayName("Fehlende Rechte sind nicht die Kanalgrenze")
	void fehlendeRechte() {
		final String text = TicketService.fehlertextAnlegen(
				ErrorResponseException.create(ErrorResponse.MISSING_PERMISSIONS, antwort()));
		assertTrue(text.startsWith("Der Kanal konnte nicht angelegt werden"), text);
	}

	/** JDA verlangt eine Antwort; ohne Netz reicht eine, die nur den Code trägt. */
	private static Response antwort() {
		return new Response(new java.io.IOException("Probe"), java.util.Set.of());
	}
}
