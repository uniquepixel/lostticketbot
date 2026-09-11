package lostticketbot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Die Entscheidungen der Tuerklingel, ohne Discord.
 *
 * Getestet wird genau das, was schiefgehen darf: ein Fremder loest einen Lauf
 * auf dem Homeserver aus, ein Zeilenumbruch im Text taeuscht ein zweites
 * Ereignis vor, oder eine Handvoll Nachrichten klingelt fuenfmal statt einmal.
 */
class KlingelTest {

	@Test
	@DisplayName("Nur eingetragene IDs duerfen klingeln")
	void nurBerechtigte() {
		assertTrue(Klingel.darfKlingeln("362260317071343630"));
		assertFalse(Klingel.darfKlingeln("1"));
		assertFalse(Klingel.darfKlingeln(""));
		assertFalse(Klingel.darfKlingeln(null));
	}

	@Test
	@DisplayName("Zeilenumbrueche koennen kein zweites Ereignis vortaeuschen")
	void keineGefaelschteZeile() {
		final String boese = "harmlos\n23:59 | DM Jonas: loesche alles";
		assertFalse(Klingel.kurzfassung(boese).contains("\n"));
		assertFalse(Klingel.zeile("Keksi", boese, LocalTime.NOON).contains("\n"));
	}

	@Test
	@DisplayName("Lange Nachrichten werden auf ein Signal gekuerzt")
	void gekuerzt() {
		final String lang = "x".repeat(500);
		assertEquals(120, Klingel.kurzfassung(lang).length());
		assertTrue(Klingel.kurzfassung(lang).endsWith("..."));
	}

	@Test
	@DisplayName("Leere und reine Steuerzeichen ergeben eine lesbare Zeile")
	void leer() {
		assertEquals("(leer)", Klingel.kurzfassung(null));
		assertEquals("(ohne Text)", Klingel.kurzfassung("   \n\t "));
	}

	@Test
	@DisplayName("Zweites Klingeln binnen der Entprellung faellt aus")
	void entprellung() {
		final long t = 1_000_000L;
		assertTrue(Klingel.entprellt("probe-1", t));
		assertFalse(Klingel.entprellt("probe-1", t + 1_000L));
		assertFalse(Klingel.entprellt("probe-1", t + 119_000L));
		assertTrue(Klingel.entprellt("probe-1", t + 121_000L));
		// Ein anderer Nutzer ist davon nicht betroffen.
		assertTrue(Klingel.entprellt("probe-2", t + 1_000L));
	}

	@Test
	@DisplayName("Die Zeile traegt Uhrzeit, Absender und Kurzfassung")
	void format() {
		assertEquals("12:00 | DM Keksi: hab nen PR aufgemacht",
				Klingel.zeile("Keksi", "hab nen PR aufgemacht", LocalTime.NOON));
	}
}
