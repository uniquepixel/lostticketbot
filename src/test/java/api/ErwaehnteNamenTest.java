package api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Ohne Datenbank und ohne Discord: nur das Einsammeln und Zuordnen. */
class ErwaehnteNamenTest {

	private static final Map<String, String> NUTZER = Map.of("111111111111111111", "Pixel", "222222222222222222", "Vize");
	private static final Map<String, String> ROLLEN = Map.of("333333333333333333", "Anführer");

	@Test
	@DisplayName("Nutzer und Rollen werden getrennt eingesammelt und benannt")
	void trennt() {
		final List<String> texte = Arrays.asList(
				"hi <@111111111111111111> und <@!222222222222222222>",
				null,
				"<@&333333333333333333> bitte, siehe <#444444444444444444>",
				"nochmal <@111111111111111111>");
		final Map<String, Map<String, String>> n = ErwaehnteNamen.aufloesen(texte, NUTZER::get, ROLLEN::get);
		assertEquals(Map.of("111111111111111111", "Pixel", "222222222222222222", "Vize"), n.get("users"));
		assertEquals(Map.of("333333333333333333", "Anführer"), n.get("roles"));
		assertEquals(2, n.size(), "Kanäle bleiben draußen");
	}

	@Test
	@DisplayName("Unbekannte, leere Namen und werfende Nachschlager fallen still weg")
	void unbekannt() {
		final List<String> texte = List.of("<@111111111111111111> <@999999999999999999> <@&333333333333333333>");
		final Map<String, Map<String, String>> n = ErwaehnteNamen.aufloesen(texte,
				id -> id.startsWith("9") ? "  " : NUTZER.get(id),
				id -> {
					throw new NumberFormatException("kein Snowflake");
				});
		assertEquals(Map.of("111111111111111111", "Pixel"), n.get("users"));
		assertTrue(n.get("roles").isEmpty());
	}

	@Test
	@DisplayName("Eine Rollen-Erwähnung ist keine Nutzer-Erwähnung, Unsinn wird nicht gelesen")
	void muster() {
		final List<String> texte = List.of("<@&333333333333333333> <@abc> <@12> <@ 111111111111111111> @111111111111111111");
		assertTrue(ErwaehnteNamen.nutzerIds(texte).isEmpty());
		assertEquals(1, ErwaehnteNamen.rollenIds(texte).size());
	}

	@Test
	@DisplayName("Die Obergrenze hält")
	void obergrenze() {
		final List<String> texte = new ArrayList<>();
		for (int i = 0; i < ErwaehnteNamen.HOECHSTENS + 50; i++) {
			texte.add("<@" + (100000000000000000L + i) + ">");
		}
		assertEquals(ErwaehnteNamen.HOECHSTENS, ErwaehnteNamen.nutzerIds(texte).size());
	}
}
