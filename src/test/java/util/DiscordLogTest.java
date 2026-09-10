package util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Zerlegung der Konsolenausgabe in Discord-Nachrichten.
 *
 * Discord nimmt 2000 Zeichen je Nachricht. Ein Log, das daran scheitert, ist
 * schlimmer als keines: es faellt erst auf, wenn man es braucht.
 */
class DiscordLogTest {

	@Test
	@DisplayName("Kurze Ausgabe bleibt ein einziger Block")
	void kurzBleibtEins() {
		final List<String> b = DiscordLog.bloecke(List.of(
				"LOST Ticket-Bot 0.1.0 ist bereit.",
				"  Server: LOST Family (733857906117574717), 11 offene Tickets"));
		assertEquals(1, b.size());
		assertTrue(b.get(0).contains("ist bereit"));
		assertTrue(b.get(0).contains("LOST Family"));
		assertTrue(b.get(0).contains("\n"), "Zeilen müssen erhalten bleiben");
	}

	@Test
	@DisplayName("Umlaute überleben — daran scheiterte die Vorlage")
	void umlauteBleibenHeil() {
		final String zeile = "Schema geprüft und aktuell. Kanal 🎫┋ticket-storage, größe: 5 KB";
		final List<String> b = DiscordLog.bloecke(List.of(zeile));
		assertEquals(1, b.size());
		assertEquals(zeile, b.get(0),
				"Die Vorlage schob einzelne Bytes durch und zerlegte damit jeden Umlaut");
	}

	@Test
	@DisplayName("Viele Zeilen werden auf mehrere Nachrichten verteilt")
	void vieleZeilen() {
		final List<String> viele = new java.util.ArrayList<>();
		for (int i = 0; i < 200; i++) {
			viele.add("Zeile " + i + " mit etwas Text, damit sie Platz braucht.");
		}
		final List<String> b = DiscordLog.bloecke(viele);

		assertTrue(b.size() > 1, "Das muss auf mehrere Nachrichten aufgeteilt werden");
		for (final String block : b) {
			assertTrue(block.length() <= DiscordLog.BLOCKGROESSE,
					"Block zu groß: " + block.length());
		}
		// Nichts darf unterwegs verloren gehen.
		assertTrue(String.join("\n", b).contains("Zeile 0 "));
		assertTrue(String.join("\n", b).contains("Zeile 199 "));
	}

	@Test
	@DisplayName("Eine einzelne überlange Zeile wird hart geschnitten statt verworfen")
	void ueberlangeZeile() {
		final String riese = "x".repeat(5000);
		final List<String> b = DiscordLog.bloecke(List.of(riese));

		assertEquals(3, b.size(), "5000 Zeichen bei 1900 je Block");
		for (final String block : b) {
			assertTrue(block.length() <= DiscordLog.BLOCKGROESSE);
		}
		assertEquals(5000, b.stream().mapToInt(t -> t.length()).sum(),
				"Kein Zeichen darf verschwinden");
	}

	@Test
	@DisplayName("Leere Eingabe erzeugt keine Nachricht")
	void leer() {
		assertTrue(DiscordLog.bloecke(List.of()).isEmpty());
	}

	@Test
	@DisplayName("Ohne Kanal-ID passiert gar nichts")
	void ohneKanal() {
		final java.io.PrintStream vorher = System.out;
		DiscordLog.setup(null);
		assertEquals(vorher, System.out,
				"Ohne konfigurierten Kanal darf System.out unangetastet bleiben");
		DiscordLog.setup("   ");
		assertEquals(vorher, System.out);
	}

	@Test
	@DisplayName("Ohne bereite Verbindung wird nicht gesendet — und nicht verworfen")
	void haeltBisJdaBereit() {
		// Beim ersten Versuch gingen genau die Startmeldungen verloren: der
		// Puffer wurde geleert, obwohl JDA noch nicht bereit war und niemand
		// sie je bekommen hatte.
		assertFalse(DiscordLog.sendenJetzt(false, true, true),
				"Ohne Verbindung darf nichts gesendet werden");
		assertFalse(DiscordLog.sendenJetzt(false, false, true));
		assertTrue(DiscordLog.sendenJetzt(true, true, false), "Voller Puffer geht raus");
		assertTrue(DiscordLog.sendenJetzt(true, false, true), "Reifer Puffer geht raus");
		assertFalse(DiscordLog.sendenJetzt(true, false, false), "Nichts zu tun");
	}

	@Test
	@DisplayName("Ein einzelner Fehlschlag schaltet den Spiegel nicht ab")
	void einFehlschlagReichtNicht() {
		// Am 10.09.2026 kam der erste Flush eine Sekunde vor "Finished
		// Loading" — der Kanalcache war noch leer, und der Spiegel schaltete
		// sich daraufhin fuer den Rest der Laufzeit ab. Genau das darf ein
		// einzelner Fehlschlag nicht mehr koennen.
		DiscordLog.fuerTestZuruecksetzen();
		DiscordLog.fuerTestScheitern("Kanal noch nicht im Cache");
		assertTrue(DiscordLog.istAn(), "Nach einem Fehlschlag muss weiter gespiegelt werden");

		DiscordLog.fuerTestScheitern("nochmal");
		DiscordLog.fuerTestScheitern("und nochmal");
		DiscordLog.fuerTestScheitern("und nochmal");
		assertTrue(DiscordLog.istAn(), "Vier Fehlschläge sind noch kein Dauerzustand");
	}

	@Test
	@DisplayName("Bei anhaltendem Scheitern gibt der Spiegel auf")
	void irgendwannGenug() {
		DiscordLog.fuerTestZuruecksetzen();
		for (int i = 0; i < 5; i++) {
			DiscordLog.fuerTestScheitern("dauerhaft kaputt");
		}
		assertFalse(DiscordLog.istAn(),
				"Sonst versucht der Bot es endlos und protokolliert dabei sich selbst");
		DiscordLog.fuerTestZuruecksetzen();
	}

	@Test
	@DisplayName("Ein Erfolg setzt den Zähler zurück")
	void erfolgSetztZurueck() {
		DiscordLog.fuerTestZuruecksetzen();
		DiscordLog.fuerTestScheitern("einmal");
		DiscordLog.fuerTestScheitern("zweimal");
		DiscordLog.fuerTestErfolg();
		for (int i = 0; i < 4; i++) {
			DiscordLog.fuerTestScheitern("später wieder");
		}
		assertTrue(DiscordLog.istAn(),
				"Nach einem Erfolg zählt die alte Pechsträhne nicht mehr mit");
		DiscordLog.fuerTestZuruecksetzen();
	}

	@Test
	@DisplayName("Ein Stacktrace bleibt zusammenhängend lesbar")
	void stacktrace() {
		final List<String> zeilen = List.of(
				"Dashboard-API: /api/stats -> java.lang.NoClassDefFoundError: absichtlich",
				"\tat api.TicketApiServer.route(TicketApiServer.java:130)",
				"\tat java.base/java.lang.Thread.run(Thread.java:1474)");
		final List<String> b = DiscordLog.bloecke(zeilen);

		assertEquals(1, b.size());
		assertTrue(b.get(0).startsWith("Dashboard-API:"));
		assertTrue(b.get(0).contains("\tat api.TicketApiServer.route"),
				"Die Einrückung macht einen Stacktrace erst lesbar");
		assertFalse(b.get(0).endsWith("\n"));
	}
}
