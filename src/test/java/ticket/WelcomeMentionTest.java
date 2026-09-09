package ticket;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Erkennung, ob ein Willkommenstext den Eroeffner schon selbst erwaehnt.
 *
 * Hintergrund: der Bot stellt dem Text eine Erwaehnung voran. Enthaelt der Text
 * ebenfalls eine, steht der Eroeffner zweimal in derselben Nachricht und
 * bekommt zwei Benachrichtigungen fuer ein Ticket. Genau das ist am 09.09.2026
 * auf LOST Family aufgefallen.
 */
class WelcomeMentionTest {

	@Test
	@DisplayName("Platzhalter {user} zaehlt als Erwaehnung")
	void platzhalter() {
		assertTrue(TicketInteractions.erwaehntEroeffner("Hey {user}, danke für deine Bewerbung."));
		assertTrue(TicketInteractions.erwaehntEroeffner("{user} Welcome"));
		assertTrue(TicketInteractions.erwaehntEroeffner("{user}"));
	}

	@Test
	@DisplayName("Bereits eingesetzte Erwaehnung zaehlt auch")
	void festeErwaehnung() {
		// Kommt vor, wenn ein Text aus einer bestehenden Nachricht kopiert wurde.
		assertTrue(TicketInteractions.erwaehntEroeffner("<@326398089298444290> Welcome"));
		assertTrue(TicketInteractions.erwaehntEroeffner("Moin <@!123>, alles klar?"));
	}

	@Test
	@DisplayName("Text ohne Erwaehnung — der Bot pingt dann selbst")
	void ohneErwaehnung() {
		assertFalse(TicketInteractions.erwaehntEroeffner("Bitte stelle dich kurz vor."));
		assertFalse(TicketInteractions.erwaehntEroeffner(""));
		assertFalse(TicketInteractions.erwaehntEroeffner(null));
		// Rollen- und Kanalerwaehnungen sind nicht der Eroeffner.
		assertFalse(TicketInteractions.erwaehntEroeffner("Frag die <@&123> oder schau in <#456>."));
	}

	@Test
	@DisplayName("Mehrzeiliger Text wird ganz durchsucht")
	void mehrzeilig() {
		assertTrue(TicketInteractions.erwaehntEroeffner("Erste Zeile\nZweite mit {user}\nDritte"));
		assertTrue(TicketInteractions.erwaehntEroeffner("Hallo\n\n<@999> bitte melden"));
	}
}
