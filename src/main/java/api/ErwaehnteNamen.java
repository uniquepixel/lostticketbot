package api;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Namen zu den Erwaehnungen in einem Ticketverlauf.
 *
 * Discord speichert eine Erwaehnung als {@code <@123>} bzw. {@code <@&456>};
 * die Website zeigte deshalb im Verlauf nur Nummern. Hier wird eingesammelt,
 * wer und welche Rolle erwaehnt ist, und der Name dazugelegt, soweit der Bot
 * ihn im Cache hat. Wer den Server verlassen hat oder eine geloeschte Rolle
 * bleibt aus — die Website zeigt dann weiter die Nummer.
 *
 * Kanaele bleiben bewusst Nummern: ein Kanalname kann etwas verraten, das der
 * Leser in Discord selbst nicht saehe.
 */
final class ErwaehnteNamen {

	private static final Pattern NUTZER = Pattern.compile("<@!?(\\d{5,20})>");
	private static final Pattern ROLLE = Pattern.compile("<@&(\\d{5,20})>");

	/** Obergrenze je Art, damit ein Ticket voller Erwaehnungen die Antwort nicht aufblaeht. */
	static final int HOECHSTENS = 200;

	private ErwaehnteNamen() {
	}

	static Set<String> nutzerIds(Iterable<String> texte) {
		return ids(NUTZER, texte);
	}

	static Set<String> rollenIds(Iterable<String> texte) {
		return ids(ROLLE, texte);
	}

	private static Set<String> ids(Pattern muster, Iterable<String> texte) {
		final Set<String> ids = new LinkedHashSet<>();
		for (final String text : texte) {
			if (text == null) {
				continue;
			}
			final Matcher m = muster.matcher(text);
			while (m.find() && ids.size() < HOECHSTENS) {
				ids.add(m.group(1));
			}
		}
		return ids;
	}

	/**
	 * @param nutzerName liefert zu einer Nutzer-ID den Namen oder {@code null}
	 * @param rollenName liefert zu einer Rollen-ID den Namen oder {@code null}
	 * @return {@code {"users": {id: name}, "roles": {id: name}}}, nur Bekannte
	 */
	static Map<String, Map<String, String>> aufloesen(Iterable<String> texte,
			Function<String, String> nutzerName, Function<String, String> rollenName) {
		final Map<String, Map<String, String>> aus = new LinkedHashMap<>();
		aus.put("users", benannt(nutzerIds(texte), nutzerName));
		aus.put("roles", benannt(rollenIds(texte), rollenName));
		return aus;
	}

	private static Map<String, String> benannt(Set<String> ids, Function<String, String> name) {
		final Map<String, String> aus = new LinkedHashMap<>();
		for (final String id : ids) {
			String n;
			try {
				n = name.apply(id);
			} catch (final RuntimeException e) {
				// Eine ID, die JDA nicht als Snowflake lesen mag, ist kein Grund,
				// den ganzen Verlauf nicht auszuliefern.
				n = null;
			}
			if (n != null && !n.isBlank()) {
				aus.put(id, n);
			}
		}
		return aus;
	}
}
