package verwaltung;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import commands.PanelCommand;
import model.Frage;
import model.Menu;
import model.Panel;
import net.dv8tion.jda.api.interactions.modals.Modal;
import ticket.Formular;

/** Prüfungen der gemeinsamen Verwaltungslogik, die ohne Datenbank und Discord auskommen. */
class VerwaltungPruefungTest {

	@Test
	@DisplayName("Emoji: Unicode und eigene Emojis gehen, Text nicht")
	void emoji() {
		assertTrue(Verwaltung.emojiGueltig("🎫"));
		assertTrue(Verwaltung.emojiGueltig("👨‍👩‍👧"));
		assertTrue(Verwaltung.emojiGueltig("1️⃣"));
		assertTrue(Verwaltung.emojiGueltig("<:lost:123456789012345678>"));
		assertTrue(Verwaltung.emojiGueltig("<a:dreh:123456789012345678>"));
		assertFalse(Verwaltung.emojiGueltig("ticket"));
		assertFalse(Verwaltung.emojiGueltig(":ticket:"));
		assertFalse(Verwaltung.emojiGueltig("🎫 a"));
		assertFalse(Verwaltung.emojiGueltig("<:x:12>"));
	}

	@Test
	@DisplayName("Stil: knoepfe/dropdown, sonst Fehler mit Satz")
	void stil() {
		assertEquals(Menu.Style.SELECT, Verwaltung.stil("dropdown"));
		assertEquals(Menu.Style.BUTTONS, Verwaltung.stil("knoepfe"));
		assertEquals(Menu.Style.BUTTONS, Verwaltung.stil(null));
		assertThrows(Verwaltung.Fehler.class, () -> Verwaltung.stil("liste"));
	}

	@Test
	@DisplayName("Felder werden über Enum- und Spaltennamen gefunden")
	void felder() {
		assertEquals(PanelCommand.Field.LOG_KANAL, Verwaltung.feld("log_kanal"));
		assertEquals(PanelCommand.Field.LOG_KANAL, Verwaltung.feld("log_channel_id"));
		assertThrows(Verwaltung.Fehler.class, () -> Verwaltung.feld("name"));
		assertThrows(Verwaltung.Fehler.class, () -> Verwaltung.feld("guild_id"));
	}

	@Test
	@DisplayName("Ungültige Zahlen und Längen werden vor dem Schreiben abgewiesen")
	void felderPruefen() {
		final Panel p = panel();
		// Kein Guild-Objekt nötig: die Prüfungen scheitern, bevor geschrieben wird.
		assertThrows(Verwaltung.Fehler.class, () -> Verwaltung.panelFelderSetzen(null, p, Map.of("STELLEN", "12")));
		assertThrows(Verwaltung.Fehler.class, () -> Verwaltung.panelFelderSetzen(null, p, Map.of("ZAEHLER", "-1")));
		assertThrows(Verwaltung.Fehler.class, () -> Verwaltung.panelFelderSetzen(null, p, Map.of("AKTIV", "vielleicht")));
		assertThrows(Verwaltung.Fehler.class, () -> Verwaltung.panelFelderSetzen(null, p, Map.of("NAME_OFFEN", "")));
		assertThrows(Verwaltung.Fehler.class,
				() -> Verwaltung.panelFelderSetzen(null, p, Map.of("EMBED_TITEL", "x".repeat(257))));
		assertThrows(Verwaltung.Fehler.class,
				() -> Verwaltung.panelFelderSetzen(null, p, Map.of("LOG_KANAL", "kein-kanal")));
	}

	@Test
	@DisplayName("Formular: höchstens fünf Fragen, Grenzen von Discord")
	void fragenGrenzen() {
		final Panel p = panel();
		final var f = new Verwaltung.FrageEingabe("Frage", null, false, true, null);
		assertThrows(Verwaltung.Fehler.class, () -> Verwaltung.fragenSetzen(p, List.of(f, f, f, f, f, f)));
		assertThrows(Verwaltung.Fehler.class, () -> Verwaltung.fragenSetzen(p,
				List.of(new Verwaltung.FrageEingabe("x".repeat(46), null, false, true, null))));
		assertThrows(Verwaltung.Fehler.class, () -> Verwaltung.fragenSetzen(p,
				List.of(new Verwaltung.FrageEingabe(" ", null, false, true, null))));
		assertThrows(Verwaltung.Fehler.class, () -> Verwaltung.fragenSetzen(p,
				List.of(new Verwaltung.FrageEingabe("ok", null, false, true, 5000))));
	}

	@Test
	@DisplayName("Formular wird als Discord-Modal gebaut")
	void modal() {
		final List<Frage> fragen = List.of(
				new Frage(1, 7, 0, "Spielername", "z. B. Simon", false, true, 30),
				new Frage(2, 7, 1, "Warum LOST?", null, true, false, null));
		final Modal m = Formular.bauen(panel(), fragen);
		assertEquals("tb:form:7", m.getId());
		assertEquals(2, m.getComponents().size());
	}

	@Test
	@DisplayName("Antworten: Zitat je Frage, leere als —, lange Antworten geteilt statt gekappt")
	void antworten() {
		final List<Frage> fragen = List.of(
				new Frage(1, 7, 0, "Name", null, false, true, null),
				new Frage(2, 7, 1, "Optional", null, false, false, null),
				new Frage(3, 7, 2, "Lang", null, true, true, null));
		final Map<String, String> a = new HashMap<>();
		a.put("f0", "Simon");
		a.put("f2", ("Wort ".repeat(700)).strip());
		final List<String> n = Formular.antwortNachrichten(fragen, a);
		assertTrue(n.size() >= 2, "4000 Zeichen passen nicht in eine Nachricht");
		n.forEach(t -> assertTrue(t.length() <= 2000, "Nachricht zu lang: " + t.length()));
		final String alles = String.join("\n", n);
		assertTrue(alles.contains("**Name**\n> Simon"));
		assertTrue(alles.contains("**Optional**\n> —"));
		assertEquals(700, alles.split("Wort", -1).length - 1, "kein Wort darf verloren gehen");
	}

	private static Panel panel() {
		return new Panel(7, "g", "Bewerbung", true, null, null, "t-{count}", "c-{count}", 0, 4,
				null, null, null, null, 1, 0, false, null, false, false);
	}
}
