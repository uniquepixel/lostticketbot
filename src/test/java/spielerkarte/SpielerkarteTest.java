package spielerkarte;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import net.dv8tion.jda.api.entities.MessageEmbed;

class SpielerkarteTest {

	private static final ObjectMapper M = new ObjectMapper();

	@Test
	@DisplayName("Tag in den Schreibweisen aus echten Bewerbungen")
	void tagFinden() {
		assertEquals(Optional.of("20QLGCJ8G"), Spielertag.finde("Rathaus: 16 Spieler-Tag: #20QLGCJ8G Andere Stats"));
		assertEquals(Optional.of("YLGC9C0GR"), Spielertag.finde("Mein Spielertag ist #YLGC9C0GR"));
		assertEquals(Optional.of("Q98YR8JYV"), Spielertag.finde(
				"lg https://link.clashofclans.com/de?action=OpenPlayerProfile&tag=Q98YR8JYV"));
		assertEquals(Optional.of("Q98YR8JYV"), Spielertag.finde("…OpenPlayerProfile&tag=%23Q98YR8JYV"));
		assertEquals(Optional.of("Q8YR2J8PC"), Spielertag.finde("#q8yr2j8pc Hey, Bin der Erik"));
	}

	@Test
	@DisplayName("Kein Tag: Kanal-Erwähnung, Farbe, Ticketnummer, Fließtext")
	void keinTag() {
		assertEquals(Optional.empty(), Spielertag.finde("schau mal in <#1547314036646084628>"));
		assertEquals(Optional.empty(), Spielertag.finde("Farbe #FF0000"));
		assertEquals(Optional.empty(), Spielertag.finde("Ticket #0829"));
		assertEquals(Optional.empty(), Spielertag.finde("#1 Clan in Deutschland"));
		assertEquals(Optional.empty(), Spielertag.finde("Hallo, ich bin Carl"));
		assertEquals(Optional.empty(), Spielertag.finde(null));
		assertEquals(Optional.empty(), Spielertag.finde("Rolle <@&1108483437662306375>"));
	}

	@Test
	@DisplayName("Karte enthält, was das Team sonst per Screenshot anfordert")
	void karte() {
		final MessageEmbed e = Spielerkarte.bauen(profil(8, 30), 0x2B2D31);
		assertEquals("Spielerkarte: Probe (#PYLQG2)", e.getTitle());
		assertTrue(e.getUrl().endsWith("tag=PYLQG2"));
		final String alles = e.getFields().stream().map(f -> f.getName() + ": " + f.getValue())
				.reduce("", (a, b) -> a + "\n" + b);
		assertTrue(alles.contains("Rathaus **17** (Waffe 2) · Erfahrung 244"), alles);
		assertTrue(alles.contains("LOST 7 (Vize) · Kriegsbereit: ja"), alles);
		assertTrue(alles.contains("Kriegssterne 799 · CWL-Sterne gesamt 289"), alles);
		assertTrue(alles.contains("insgesamt 539.819"), alles);
		// Höchste Stufe zuerst, Bauarbeiterbasis nicht dabei
		assertTrue(e.getFields().get(5).getValue().startsWith("Held7 108 · Held6 107"), alles);
		assertFalse(alles.contains("Nachtheld"), alles);
	}

	@Test
	@DisplayName("Lange Ausrüstungsliste bleibt unter Discords 1024 Zeichen")
	void langeListe() {
		final MessageEmbed e = Spielerkarte.bauen(profil(8, 120), 0);
		final String ausr = e.getFields().get(6).getValue();
		assertTrue(ausr.length() <= 1024, "Länge " + ausr.length());
		assertTrue(ausr.matches(".*\\+\\d+ weitere$"), ausr);
	}

	@Test
	@DisplayName("Ohne Clan und ohne Helden keine Lücke und kein Absturz")
	void minimal() {
		final ObjectNode p = M.createObjectNode().put("tag", "#PYLQG2").put("name", "Neu").put("townHallLevel", 3);
		final MessageEmbed e = Spielerkarte.bauen(p, 0);
		assertTrue(e.getFields().get(1).getValue().startsWith("kein Clan"));
		assertEquals("–", e.getFields().get(5).getValue());
	}

	@Test
	@DisplayName("Je Ticket jeder Tag einmal, höchstens drei Karten")
	void begrenzung() {
		final SpielerkartenListener l = new SpielerkartenListener("1");
		assertTrue(l.vormerken(1, "A"));
		assertFalse(l.vormerken(1, "A"));
		assertTrue(l.vormerken(1, "B"));
		assertTrue(l.vormerken(1, "C"));
		assertFalse(l.vormerken(1, "D"));
		assertTrue(l.vormerken(2, "A"));
	}

	@Test
	@DisplayName("Ohne Einstellung aus")
	void standardAus() {
		assertFalse(new SpielerkartenListener((String) null).an());
		assertFalse(new SpielerkartenListener(" , ").an());
		assertTrue(new SpielerkartenListener("1108449987827876022").an());
	}

	@Test
	@DisplayName("Umschalter-Datei: url und key werden gelesen")
	void umschalter() {
		assertEquals(Optional.of("https://x"), CocSpieler.wert("url=https://x\nkey=abc\n", "url"));
		assertEquals(Optional.of("abc"), CocSpieler.wert("url=https://x\r\nkey=abc", "key"));
		assertEquals(Optional.empty(), CocSpieler.wert("url=https://x", "key"));
	}

	private static JsonNode profil(int helden, int ausruestung) {
		final ObjectNode p = M.createObjectNode().put("tag", "#PYLQG2").put("name", "Probe")
				.put("townHallLevel", 17).put("townHallWeaponLevel", 2).put("expLevel", 244)
				.put("bestTrophies", 3716).put("warStars", 799).put("donations", 909)
				.put("role", "coLeader").put("warPreference", "in");
		p.putObject("clan").put("name", "LOST 7");
		p.putObject("leagueTier").put("name", "P.E.K.K.A League 23");
		final ArrayNode a = p.putArray("achievements");
		a.addObject().put("name", "War League Legend").put("value", 289);
		a.addObject().put("name", "Friend in Need").put("value", 539819);
		final ArrayNode h = p.putArray("heroes");
		for (int i = 0; i < helden; i++) {
			h.addObject().put("name", "Held" + i).put("level", 101 + i).put("village", "home");
		}
		h.addObject().put("name", "Nachtheld").put("level", 200).put("village", "builderBase");
		final ArrayNode q = p.putArray("heroEquipment");
		for (int i = 0; i < ausruestung; i++) {
			q.addObject().put("name", "Ausrüstungsstück Nummer " + i).put("level", i % 27).put("village", "home");
		}
		return p;
	}
}
