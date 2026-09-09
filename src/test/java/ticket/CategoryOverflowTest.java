package ticket;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Erkennung von Folgekategorien bei Discords 50-Kanal-Limit.
 *
 * Die Beispiele sind die echten Kategorienamen des Bewerbungsservers.
 */
class CategoryOverflowTest {

	@Test
	@DisplayName("Laufende Nummer am Ende wird abgeschnitten")
	void nummerAbschneiden() {
		assertEquals("ANGENOMMEN", TicketService.stripTrailingNumber("ANGENOMMEN 1"));
		assertEquals("ANGENOMMEN", TicketService.stripTrailingNumber("ANGENOMMEN 5"));
		assertEquals("Angenommen CR", TicketService.stripTrailingNumber("Angenommen CR 2"));
	}

	@Test
	@DisplayName("Kategorien ohne Nummer bleiben unveraendert")
	void ohneNummer() {
		assertEquals("CR Tickets", TicketService.stripTrailingNumber("CR Tickets"));
		assertEquals("Bewerbungen", TicketService.stripTrailingNumber("Bewerbungen"));
		assertEquals("Mülleimer", TicketService.stripTrailingNumber("Mülleimer"));
	}

	@Test
	@DisplayName("Eine Zahl am Ende gehoert manchmal zum Namen - bekannte Einschraenkung")
	void zahlAmEndeGehoertZumNamen() {
		// "LOST F2P 2" ist ein Clan, keine zweite Ausweichkategorie. Der Bot
		// wuerde hier "LOST F2P" als Basis nehmen und im Ueberlauffall
		// faelschlich nach Geschwistern suchen. Bewusst in Kauf genommen, weil
		// die Ausweichkategorien im Bestand ausschliesslich "ANGENOMMEN n"
		// heissen - festgehalten, damit es bei einer Umbenennung auffaellt.
		assertEquals("LOST F2P", TicketService.stripTrailingNumber("LOST F2P 2"));
		assertEquals("CWL - LOST 3 CWL", TicketService.stripTrailingNumber("CWL - LOST 3 CWL 2"));
	}

	@Test
	@DisplayName("Leere und fehlende Namen stuerzen nicht ab")
	void randfaelle() {
		assertEquals("", TicketService.stripTrailingNumber(null));
		assertEquals("", TicketService.stripTrailingNumber("   "));
		assertEquals("", TicketService.stripTrailingNumber("42"));
	}
}
