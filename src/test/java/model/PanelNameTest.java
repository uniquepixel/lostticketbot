package model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Namensbildung fuer Ticketkanaele.
 *
 * Die Testfaelle stammen aus dem echten Bestand beider LOST-Server: die
 * Zaehlerstaende, die uneinheitliche Auffuellung und die Eigenheit des
 * Orga-Panels sind so vorgefunden worden, nicht ausgedacht.
 */
class PanelNameTest {

	private static Panel panel(String open, String closed, int padding) {
		return new Panel(1L, "g", "Test", true, null, null, open, closed, 0, padding,
				null, null, null, null, 1, 0, false, null, false, false);
	}

	@Test
	@DisplayName("Zaehler wird auf die konfigurierte Stellenzahl aufgefuellt")
	void padding() {
		final Panel p = panel("th17-{count}", "th17-closed-{count}", 4);
		assertEquals("th17-0001", p.renderName(false, 1, "wer"));
		assertEquals("th17-0454", p.renderName(false, 454, "wer"));
		assertEquals("th17-closed-0454", p.renderName(true, 454, "wer"));
	}

	@Test
	@DisplayName("Padding 1 bedeutet gar keine Auffuellung - so steht das F2P-Panel im Bestand")
	void ohnePadding() {
		final Panel p = panel("lost-f2p-2-{count}", "lost-f2p-2-closed-{count}", 1);
		assertEquals("lost-f2p-2-7", p.renderName(false, 7, "wer"));
		assertEquals("lost-f2p-2-249", p.renderName(false, 249, "wer"));
	}

	@Test
	@DisplayName("Zaehler laeuft ueber die Stellenzahl hinaus, statt abgeschnitten zu werden")
	void ueberlauf() {
		final Panel p = panel("lost-gp-{count}", "lost-gp-closed-{count}", 4);
		assertEquals("lost-gp-12345", p.renderName(false, 12345, "wer"));
	}

	@Test
	@DisplayName("Orga-Panel wirft beim Schliessen den Praefix weg - Bestand von LOST Family")
	void orgaSchema() {
		final Panel p = panel("orga-ticket-{count}", "closed-{count}", 4);
		assertEquals("orga-ticket-0814", p.renderName(false, 814, "wer"));
		assertEquals("closed-0815", p.renderName(true, 815, "wer"));
	}

	@Test
	@DisplayName("{user} wird ersetzt und mitnormalisiert")
	void benutzername() {
		final Panel p = panel("ticket-{user}", "ticket-closed-{user}", 4);
		assertEquals("ticket-simon-nnn", p.renderName(false, 1, "Simon NNN"));
	}

	@Test
	@DisplayName("Fehlender Benutzername macht keinen Namen kaputt")
	void benutzernameFehlt() {
		final Panel p = panel("ticket-{user}-{count}", "ticket-closed-{count}", 4);
		assertEquals("ticket-0001", p.renderName(false, 1, null));
	}

	// --- Normalisierung ----------------------------------------------------

	@Test
	@DisplayName("Discord erzwingt Kleinschreibung und Bindestriche - genau das simulieren wir")
	void normalisierung() {
		assertEquals("th-15-0622", Panel.sanitizeChannelName("TH 15-0622"));
		assertEquals("lost-cr-1317", Panel.sanitizeChannelName("Lost CR 1317"));
		assertEquals("a-b", Panel.sanitizeChannelName("a___b"));
	}

	@Test
	@DisplayName("Der Tippfehler aus dem Bestand ist reproduzierbar")
	void bestandsTippfehler() {
		// Im Ticket-Tool-Dashboard steht "TH 15-{count}" mit Leerzeichen.
		// Discord macht daraus "th-15-...", was niemandem aufgefallen ist,
		// weil das Ergebnis plausibel aussieht.
		final Panel mitTippfehler = panel("TH 15-{count}", "TH 15-Closed-{count}", 4);
		assertEquals("th-15-0622", mitTippfehler.renderName(false, 622, null));

		final Panel bereinigt = panel("th15-{count}", "th15-closed-{count}", 4);
		assertEquals("th15-0622", bereinigt.renderName(false, 622, null));
	}

	@Test
	@DisplayName("Rand- und Sonderfaelle ergeben nie einen leeren oder zu langen Namen")
	void randfaelle() {
		assertEquals("ticket", Panel.sanitizeChannelName("!!!"));
		assertEquals("ticket", Panel.sanitizeChannelName(""));
		assertEquals("ticket", Panel.sanitizeChannelName("---"));
		assertTrue(Panel.sanitizeChannelName("x".repeat(300)).length() <= 100);
		// Umlaute bleiben, Discord laesst sie zu
		assertEquals("grösse", Panel.sanitizeChannelName("Grösse"));
	}
}
