package transcript;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import net.dv8tion.jda.api.entities.EmbedType;
import net.dv8tion.jda.api.entities.MessageEmbed;

/**
 * Embeds einer Nachricht fuer den Verlauf: nach JSON und zurueck.
 *
 * Bis zum 20.11.2026 schrieb der Mitschnitt nur den Text einer Nachricht. Was
 * ein Bot als Embed schickt, hat aber gar keinen Text: die Begruessung im
 * Ticket, die Spielerauskunft des LOST Managers, die das Team sich im
 * Bewerbungsticket ansieht. Im Archiv, im Transcript und im Dashboard stand
 * davon nur eine leere Zeile (56 Ausgaben des LOST Managers in 41 Tickets).
 *
 * Festgehalten wird nur, was sich lesen laesst: Titel, Text, Felder, Autor,
 * Fusszeile und die Adressen von Bild und Vorschaubild. Farbe und Zeitstempel
 * nicht. Linkvorschauen, die Discord an gewoehnliche Nachrichten haengt, auch
 * nicht — die wiederholen nur die Seite hinter dem Link.
 */
public final class Einbettungen {

	private static final ObjectMapper JSON = new ObjectMapper();

	private Einbettungen() {
	}

	/** Ein Feld eines Embeds. */
	public record Feld(String name, String wert) {
	}

	/** Ein Embed, so weit es der Verlauf festhaelt. Fehlendes ist {@code null}. */
	public record Einbettung(String autor, String titel, String url, String text,
			List<Feld> felder, String bild, String vorschaubild, String fusszeile) {

		/** Hat das Embed irgendetwas, das sich zeigen laesst? */
		public boolean leer() {
			return autor == null && titel == null && text == null && felder.isEmpty()
					&& bild == null && vorschaubild == null && fusszeile == null;
		}
	}

	/**
	 * Macht aus den Embeds einer Nachricht das, was in {@code embeds_json}
	 * steht.
	 *
	 * @return JSON-Liste oder {@code null}, wenn nichts Festhaltenswertes dabei ist
	 */
	public static String alsJson(List<MessageEmbed> embeds) {
		final List<Einbettung> liste = new ArrayList<>();
		if (embeds != null) {
			for (final MessageEmbed e : embeds) {
				if (e == null || e.getType() != EmbedType.RICH) {
					continue;
				}
				final List<Feld> felder = new ArrayList<>();
				for (final MessageEmbed.Field f : e.getFields()) {
					felder.add(new Feld(f.getName(), f.getValue()));
				}
				liste.add(new Einbettung(
						e.getAuthor() == null ? null : e.getAuthor().getName(),
						e.getTitle(),
						e.getUrl(),
						e.getDescription(),
						felder,
						e.getImage() == null ? null : e.getImage().getUrl(),
						e.getThumbnail() == null ? null : e.getThumbnail().getUrl(),
						e.getFooter() == null ? null : e.getFooter().getText()));
			}
		}
		return alsJsonText(liste);
	}

	/** Wie {@link #alsJson}, aber aus bereits gelesenen Embeds — getrennt, damit es ohne Discord pruefbar ist. */
	static String alsJsonText(List<Einbettung> liste) {
		final ArrayNode feld = JSON.createArrayNode();
		for (final Einbettung e : liste) {
			final Einbettung sauber = new Einbettung(leerZuNull(e.autor()), leerZuNull(e.titel()),
					leerZuNull(e.url()), leerZuNull(e.text()),
					e.felder() == null ? List.of() : e.felder(),
					leerZuNull(e.bild()), leerZuNull(e.vorschaubild()), leerZuNull(e.fusszeile()));
			if (sauber.leer()) {
				continue;
			}
			final ObjectNode o = feld.addObject();
			setze(o, "author", sauber.autor());
			setze(o, "title", sauber.titel());
			setze(o, "url", sauber.url());
			setze(o, "description", sauber.text());
			if (!sauber.felder().isEmpty()) {
				final ArrayNode felder = o.putArray("fields");
				for (final Feld f : sauber.felder()) {
					final ObjectNode fo = felder.addObject();
					fo.put("name", ohneNull(f.name() == null ? "" : f.name()));
					fo.put("value", ohneNull(f.wert() == null ? "" : f.wert()));
				}
			}
			setze(o, "image", sauber.bild());
			setze(o, "thumbnail", sauber.vorschaubild());
			setze(o, "footer", sauber.fusszeile());
		}
		return feld.isEmpty() ? null : feld.toString();
	}

	/**
	 * Liest {@code embeds_json} wieder ein. Unlesbares oder Fehlendes ergibt
	 * eine leere Liste — ein kaputtes Embed darf kein Transcript verhindern.
	 */
	public static List<Einbettung> lesen(String json) {
		final List<Einbettung> liste = new ArrayList<>();
		final JsonNode wurzel = alsKnoten(json);
		if (wurzel == null) {
			return liste;
		}
		for (final JsonNode o : wurzel) {
			if (!o.isObject()) {
				continue;
			}
			final List<Feld> felder = new ArrayList<>();
			final JsonNode fs = o.get("fields");
			if (fs != null && fs.isArray()) {
				for (final JsonNode f : fs) {
					felder.add(new Feld(text(f, "name"), text(f, "value")));
				}
			}
			final Einbettung e = new Einbettung(text(o, "author"), text(o, "title"), text(o, "url"),
					text(o, "description"), felder, text(o, "image"), text(o, "thumbnail"),
					text(o, "footer"));
			if (!e.leer()) {
				liste.add(e);
			}
		}
		return liste;
	}

	/**
	 * Dieselben Daten als JSON-Knoten, fuer Archiv und Dashboard-API.
	 *
	 * @return die Liste oder {@code null}, wenn nichts oder nichts Lesbares da ist
	 */
	public static JsonNode alsKnoten(String json) {
		if (json == null || json.isBlank()) {
			return null;
		}
		try {
			final JsonNode wurzel = JSON.readTree(json);
			return wurzel != null && wurzel.isArray() && !wurzel.isEmpty() ? wurzel : null;
		} catch (final Exception e) {
			return null;
		}
	}

	private static void setze(ObjectNode o, String name, String wert) {
		if (wert != null) {
			o.put(name, ohneNull(wert));
		}
	}

	private static String text(JsonNode o, String name) {
		final JsonNode n = o == null ? null : o.get(name);
		return n == null || !n.isTextual() ? null : leerZuNull(n.asText());
	}

	private static String leerZuNull(String s) {
		return s == null || s.isBlank() ? null : s;
	}

	/** Postgres nimmt in jsonb kein Nullzeichen an; die ganze Zeile ginge sonst verloren. */
	private static String ohneNull(String s) {
		return s.indexOf('\u0000') < 0 ? s : s.replace("\u0000", "");
	}
}
