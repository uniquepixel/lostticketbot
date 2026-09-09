package migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import migration.AdoptionService.Treffer;
import model.Panel;

/**
 * Zuordnung bestehender Ticketkanaele zu Panels.
 *
 * Alle Kanalnamen hier sind echt — am 09.09.2026 von beiden LOST-Servern
 * gelesen. Die Faelle mit Namenszusatz ("-angenommen") und die ueberlappenden
 * Praefixe sind der Grund, warum die naive Loesung (letzte Zahl im Namen,
 * erstes passendes Panel) nicht reicht.
 *
 * Zur Einordnung: OB ein Kanal ein Ticket ist, entscheidet nicht der Name,
 * sondern ob Ticket Tool die erste Nachricht darin geschrieben hat. Der Name
 * liefert nur die laufende Nummer und einen Hinweis aufs Panel. Deshalb ist es
 * hier auch unkritisch, wenn ein Name gar nicht passt.
 */
class AdoptionServiceTest {

	private static Panel panel(long id, String name, String open, String closed) {
		return new Panel(id, "g", name, true, null, null, open, closed, 0, 4,
				null, null, null, null, 1, 0, false, null, false, false);
	}

	/** Panels des Bewerbungsservers, so wie sie im Seed stehen. */
	private static final List<Panel> BEWERBUNGEN = List.of(
			panel(1, "CR-lost", "lost-cr-{count}", "lost-cr-closed-{count}"),
			panel(2, "BS-lost", "lost-bs-{count}", "lost-bs-closed-{count}"),
			panel(3, "Bewerbung für LOST GP", "lost-gp-{count}", "lost-gp-closed-{count}"),
			panel(4, "F2P", "lost-f2p-2-{count}", "lost-f2p-2-closed-{count}"),
			panel(5, "Rh18", "th18-{count}", "th18-closed-{count}"),
			panel(6, "Rh17", "th17-{count}", "th17-closed-{count}"),
			panel(7, "Rh16", "th16-{count}", "th16-closed-{count}"));

	/** Panels von LOST Family. Das Orga-Panel hat ein abweichendes Schliess-Muster. */
	private static final List<Panel> FAMILY = List.of(
			panel(10, "Orga Ticket", "orga-ticket-{count}", "closed-{count}"),
			panel(11, "Lost 4", "lost-4-{count}", "lost-4-closed-{count}"),
			panel(12, "Lost 5", "lost-5-{count}", "lost-5-closed-{count}"),
			panel(13, "Lost 7", "lost-7-{count}", "lost-7-closed-{count}"),
			panel(14, "Lost F2P", "lost-f2p-{count}", "lost-f2p-closed-{count}"));

	private static Treffer treffer(List<Panel> panels, String name) {
		final Optional<Treffer> t = AdoptionService.zuordnen(panels, name);
		assertTrue(t.isPresent(), () -> "'" + name + "' wurde keinem Panel zugeordnet");
		return t.get();
	}

	// -----------------------------------------------------------------------

	@Test
	@DisplayName("Praefix wird aus dem Namensmuster abgeleitet")
	void praefixAbleiten() {
		assertEquals("th18-", AdoptionService.praefix("th18-{count}"));
		assertEquals("lost-f2p-2-", AdoptionService.praefix("lost-f2p-2-{count}"));
		assertEquals("closed-", AdoptionService.praefix("closed-{count}"));
		assertEquals("", AdoptionService.praefix(null));
	}

	@Test
	@DisplayName("Einfache Faelle aus dem Bestand")
	void einfach() {
		assertEquals(452, treffer(BEWERBUNGEN, "th17-0452").nummer());
		assertEquals("Rh17", treffer(BEWERBUNGEN, "th17-0452").panel().name());
		assertEquals(264, treffer(BEWERBUNGEN, "lost-bs-0264").nummer());
		assertEquals(1316, treffer(BEWERBUNGEN, "lost-cr-1316").nummer());
		assertEquals(814, treffer(FAMILY, "orga-ticket-0814").nummer());
		assertEquals(89, treffer(FAMILY, "lost-4-0089").nummer());
	}

	@Test
	@DisplayName("Namenszusatz hinter der Nummer wird erkannt, nicht verschluckt")
	void namenszusatz() {
		// Eure Leader haengen den Status an den Kanalnamen. Eine naive Suche
		// nach der LETZTEN Zahl im Namen findet hier gar keine.
		final Treffer t = treffer(BEWERBUNGEN, "th18-0135-angenommen");
		assertEquals(135, t.nummer());
		assertEquals("Rh18", t.panel().name());
		assertEquals("-angenommen", t.zusatz());

		assertEquals(251, treffer(BEWERBUNGEN, "lost-f2p-2-251-angenommen").nummer());
		assertEquals(152, treffer(BEWERBUNGEN, "th18-0152-angenommen").nummer());
	}

	@Test
	@DisplayName("Der laengere Praefix gewinnt — sonst wird die Nummer falsch gelesen")
	void laengsterTreffer() {
		// Beide Panels im selben Server: "lost-f2p-" und "lost-f2p-2-".
		final List<Panel> beide = List.of(
				panel(1, "Lost F2P", "lost-f2p-{count}", "lost-f2p-closed-{count}"),
				panel(2, "Lost F2P 2", "lost-f2p-2-{count}", "lost-f2p-2-closed-{count}"));

		final Treffer t = treffer(beide, "lost-f2p-2-250");
		assertEquals("Lost F2P 2", t.panel().name(), "Sonst landet das Ticket im falschen Clan");
		assertEquals(250, t.nummer(), "Der kurze Praefix wuerde hier 2 lesen statt 250");

		assertEquals("Lost F2P", treffer(beide, "lost-f2p-0033").panel().name());
		assertEquals(33, treffer(beide, "lost-f2p-0033").nummer());
	}

	@Test
	@DisplayName("Geschlossene Tickets werden als solche erkannt")
	void geschlossenErkannt() {
		final Treffer t = treffer(BEWERBUNGEN, "lost-cr-closed-1318");
		assertTrue(t.geschlossen());
		assertEquals(1318, t.nummer(), "Nicht die 'lost-cr-'-Deutung, die hier gar keine Zahl faende");
		assertEquals("CR-lost", t.panel().name());

		assertFalse(treffer(BEWERBUNGEN, "lost-cr-1316").geschlossen());
	}

	@Test
	@DisplayName("Das Orga-Panel wirft beim Schliessen den Praefix weg")
	void orgaSonderfall() {
		// "orga-ticket-0814" -> "closed-0815": das Schliess-Muster teilt sich
		// keinen Praefix mit dem Offen-Muster.
		final Treffer offen = treffer(FAMILY, "orga-ticket-0814");
		assertFalse(offen.geschlossen());
		assertEquals(814, offen.nummer());

		final Treffer zu = treffer(FAMILY, "closed-0815");
		assertTrue(zu.geschlossen());
		assertEquals(815, zu.nummer());
		assertEquals("Orga Ticket", zu.panel().name());
	}

	@Test
	@DisplayName("Kanaele, die keine Tickets sind, werden nicht angefasst")
	void keineFalschenTreffer() {
		// Alles echte Kanalnamen von beiden Servern, die zufaellig aehnlich
		// heissen. Wuerde einer davon uebernommen, haetten wir einen
		// Besprechungskanal als Bewerbung in der Datenbank.
		for (final String name : List.of(
				"lost-3-4-5-6-7", "lost-f2p-1-2", "lost-3-besprechung", "lost-3-kopieren",
				"lost-cr", "lost-bs", "lost-gp", "ticket-wüste-lost-5", "lost-7-vize",
				"übersicht-coc", "leader-chat", "transcripts")) {
			assertTrue(AdoptionService.zuordnen(BEWERBUNGEN, name).isEmpty(),
					() -> "'" + name + "' haette nicht zugeordnet werden duerfen");
		}
	}

	@Test
	@DisplayName("Panel-Kanaele mit Bindestrich-Zahlen loesen keinen Treffer aus")
	void panelKanaeleBleibenAussen() {
		// "lost-3-4-5-6-7" ist der Kanal mit dem Rathaus-Dropdown. Gaebe es ein
		// Panel "lost-3-", waere das ein Treffer mit Nummer 4 — deshalb hier
		// festgehalten, dass Family-Panels auf dem Bewerbungsserver nichts zu
		// suchen haben.
		final Treffer t = treffer(FAMILY, "lost-4-0089");
		assertEquals(89, t.nummer());
		assertTrue(AdoptionService.zuordnen(FAMILY, "lost-4-vize").isEmpty());
	}

	@Test
	@DisplayName("Panel wird am Ticket-Typ aus der Willkommensnachricht erkannt")
	void panelAusTypErkennen() {
		final List<Panel> mitTiteln = List.of(
				new Panel(1, "g", "Rh17", true, null, null, "th17-{count}", "th17-closed-{count}",
						0, 4, null, "Rathaus 17", "Rathaus 17", null, 1, 0, false, null, false, false),
				new Panel(2, "g", "CR-lost", true, null, null, "lost-cr-{count}", "lost-cr-closed-{count}",
						0, 4, null, "LOST - Clash Royale Clan", null, null, 1, 0, false, null, false, false));

		// Genau die Embed-Beschreibung, die im echten Ticket th17-0449 steht.
		assertEquals("Rh17", AdoptionService.panelAusTyp(mitTiteln, "Rathaus 17").orElseThrow().name());
		// Gross-/Kleinschreibung und Leerzeichen sollen nicht stoeren.
		assertEquals("Rh17", AdoptionService.panelAusTyp(mitTiteln, "  rathaus 17 ").orElseThrow().name());
		// Der abweichende Embed-Titel von CR-lost wird ebenfalls gefunden.
		assertEquals("CR-lost",
				AdoptionService.panelAusTyp(mitTiteln, "LOST - Clash Royale Clan").orElseThrow().name());
		// Ueber den Panelnamen selbst geht es auch.
		assertEquals("Rh17", AdoptionService.panelAusTyp(mitTiteln, "Rh17").orElseThrow().name());

		assertTrue(AdoptionService.panelAusTyp(mitTiteln, "Rathaus 42").isEmpty());
		assertTrue(AdoptionService.panelAusTyp(mitTiteln, null).isEmpty());
		assertTrue(AdoptionService.panelAusTyp(mitTiteln, "  ").isEmpty());
	}

	@Test
	@DisplayName("Umbenanntes Ticket: Name liefert keine Nummer, das Ticket bleibt trotzdem gueltig")
	void umbenanntesTicket() {
		// "ticket-wueste-lost-5" ist laut Jonas kein Ticket - aber ein Ticket
		// KOENNTE so heissen, wenn jemand es umbenennt. Dann findet die
		// Namensauswertung nichts, und genau darauf muss der Ablauf gefasst
		// sein: erkannt wird ueber die erste Nachricht, die Nummer faellt weg.
		assertTrue(AdoptionService.zuordnen(BEWERBUNGEN, "besprechung-mit-simon").isEmpty());
		assertTrue(AdoptionService.zuordnen(BEWERBUNGEN, "ticket-wüste-lost-5").isEmpty());
	}

	@Test
	@DisplayName("Erste Zahl nach dem Praefix, nicht irgendeine im Namen")
	void zahlErkennung() {
		assertEquals(135, AdoptionService.ersteZahlNach("th18-0135-angenommen", "th18-").wert());
		assertEquals(4, AdoptionService.ersteZahlNach("th18-0135-angenommen", "th18-").laenge());
		assertTrue(AdoptionService.ersteZahlNach("th18-abc", "th18-").fehlt());
		assertTrue(AdoptionService.ersteZahlNach("th18-", "th18-").fehlt());
		assertTrue(AdoptionService.ersteZahlNach("anderes", "th18-").fehlt());
	}
}
