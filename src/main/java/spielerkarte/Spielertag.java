package spielerkarte;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Findet einen Clash-of-Clans-Spielertag in einer Nachricht.
 *
 * Bewerber schreiben ihn auf drei Arten: "#Q98YR8JYV", "Spieler-Tag: #20QLGCJ8G"
 * oder als Link aus dem Spiel (`…OpenPlayerProfile&tag=Q98YR8JYV`). Supercell
 * benutzt für Tags nur die Zeichen 0289PYLQGRJCUV — das hält Kanal-Erwähnungen
 * (`<#1547…>`) und Farbcodes (`#FF0000`) draußen.
 */
public final class Spielertag {

	private static final Pattern TAG = Pattern.compile(
			"(?<![<\\w])(?:#|tag=(?:%23|#)?)([0289PYLQGRJCUV]{5,12})(?![0-9A-Z])",
			Pattern.CASE_INSENSITIVE);

	private Spielertag() {
	}

	/** Erster Tag in der Nachricht, groß geschrieben und ohne '#'. */
	public static Optional<String> finde(String text) {
		if (text == null || text.isEmpty()) {
			return Optional.empty();
		}
		final Matcher m = TAG.matcher(text);
		return m.find() ? Optional.of(m.group(1).toUpperCase(Locale.ROOT)) : Optional.empty();
	}
}
