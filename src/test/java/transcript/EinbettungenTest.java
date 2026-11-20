package transcript;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.EmbedType;
import net.dv8tion.jda.api.entities.MessageEmbed;
import transcript.Einbettungen.Einbettung;
import transcript.Einbettungen.Feld;

/**
 * Embeds im Verlauf: was ein Bot als Embed schickt, hat keinen Text und stand
 * bis 20.11.2026 als leere Zeile in Archiv und Transcript. Ohne Datenbank und
 * ohne Discord.
 */
class EinbettungenTest {

	@Test
	@DisplayName("Ein Embed geht mit Titel, Text, Feldern, Autor und Fußzeile nach JSON und zurück")
	void hinUndZurueck() {
		final MessageEmbed embed = new EmbedBuilder()
				.setAuthor("LOST Manager")
				.setTitle("Spieler", "https://example.org/spieler")
				.setDescription("Rathaus 16\nzweite Zeile")
				.addField("Clan", "LOST 4", true)
				.addField("Rolle", "Ältester", true)
				.setImage("https://example.org/bild.png")
				.setFooter("Stand heute")
				.build();

		final List<Einbettung> gelesen = Einbettungen.lesen(Einbettungen.alsJson(List.of(embed)));

		assertEquals(1, gelesen.size());
		final Einbettung e = gelesen.get(0);
		assertEquals("LOST Manager", e.autor());
		assertEquals("Spieler", e.titel());
		assertEquals("https://example.org/spieler", e.url());
		assertEquals("Rathaus 16\nzweite Zeile", e.text());
		assertEquals(List.of(new Feld("Clan", "LOST 4"), new Feld("Rolle", "Ältester")), e.felder());
		assertEquals("https://example.org/bild.png", e.bild());
		assertEquals("Stand heute", e.fusszeile());
	}

	@Test
	@DisplayName("Keine Embeds, leere Embeds und Linkvorschauen ergeben nichts")
	void nichtsFesthaltenswertes() {
		assertNull(Einbettungen.alsJson(List.of()));
		assertNull(Einbettungen.alsJson(null));
		assertNull(Einbettungen.alsJsonText(List.of(
				new Einbettung(null, " ", null, "", List.of(), null, null, null))));

		final MessageEmbed vorschau = new MessageEmbed("https://example.org", "Seitentitel", "Seitentext",
				EmbedType.LINK, null, 0, null, null, null, null, null, null, List.of());
		assertNull(Einbettungen.alsJson(List.of(vorschau)),
				"Die Linkvorschau einer gewöhnlichen Nachricht gehört nicht in den Verlauf");
	}

	@Test
	@DisplayName("Ein Nullzeichen kostet nicht die ganze Zeile (jsonb nimmt es nicht an)")
	void nullzeichen() {
		final String json = Einbettungen.alsJsonText(List.of(
				new Einbettung(null, "Ti\u0000tel", null, "Te\u0000xt",
						List.of(new Feld("N\u0000", "W\u0000")), null, null, null)));
		assertFalse(json.contains("\\u0000"), json);
		final Einbettung e = Einbettungen.lesen(json).get(0);
		assertEquals("Titel", e.titel());
		assertEquals("Text", e.text());
		assertEquals(new Feld("N", "W"), e.felder().get(0));
	}

	@Test
	@DisplayName("Unlesbares in der Spalte ergibt eine leere Liste statt eines Fehlers")
	void unlesbares() {
		assertTrue(Einbettungen.lesen(null).isEmpty());
		assertTrue(Einbettungen.lesen("").isEmpty());
		assertTrue(Einbettungen.lesen("kein json").isEmpty());
		assertTrue(Einbettungen.lesen("{\"title\":\"kein Feld\"}").isEmpty());
		assertTrue(Einbettungen.lesen("[1, \"zwei\", {}]").isEmpty());
		assertNull(Einbettungen.alsKnoten("[]"));
		assertEquals(1, Einbettungen.lesen("[{\"title\":5,\"description\":\"geht\",\"fields\":\"nein\"}]").size());
	}

	@Test
	@DisplayName("Im Transcript wird ein Embed maskiert, und nur http(s) wird zum Link")
	void html() {
		final String html = HtmlRenderer.embedHtml(new Einbettung("<b>Autor</b>", "<script>alert(1)</script>",
				"javascript:alert(1)", "Hallo <@123>\nZeile <img src=x>",
				List.of(new Feld("<i>Name</i>", null)), "javascript:alert(2)",
				"https://example.org/a.png?x=\"1\"", "Fuß & Zeile"));

		assertFalse(html.contains("<script>"), html);
		assertFalse(html.contains("<img"), html);
		assertFalse(html.contains("<b>Autor"), html);
		assertFalse(html.contains("<i>Name"), html);
		assertFalse(html.contains("href=\"javascript"), html);
		assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"), html);
		assertTrue(html.contains("<span class=\"erw\">@123</span><br>Zeile"), html);
		assertTrue(html.contains("href=\"https://example.org/a.png?x=&quot;1&quot;\""), html);
		assertTrue(html.contains("Fuß &amp; Zeile"), html);

		final String mitLink = HtmlRenderer.embedHtml(new Einbettung(null, "Titel", "https://example.org/",
				null, List.of(), null, null, null));
		assertTrue(mitLink.contains("<a href=\"https://example.org/\""), mitLink);
	}
}
