package lostticketbot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import javax.annotation.Nonnull;

import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

/**
 * Tuerklingel: schreibt eine Zeile, wenn ein Berechtigter dem Bot eine
 * Direktnachricht schickt.
 *
 * Warum es das gibt: Keksis Pull Requests und Issues sollen den Assistenten
 * erreichen, ohne dass Jonas Bescheid sagt. Ueber GitHub allein dauert das bis
 * zur naechsten stuendlichen Wache. Der Bot haengt ohnehin an einer offenen
 * Gateway-Verbindung und erfaehrt eine DM in Echtzeit — er muss sie nur
 * weiterreichen.
 *
 * Bewusst schmal gehalten: diese Klasse startet nichts und fuehrt nichts aus.
 * Sie haengt eine Zeile an eine Datei. Alles Weitere entscheidet der Assistent,
 * der diese Datei beobachtet. Damit ist die Angriffsflaeche einer fremden
 * Nachricht genau eine Textzeile in einem Log.
 */
public class Klingel extends ListenerAdapter {

	/** Wer klingeln darf. Komma-getrennte Discord-IDs, sonst nur Keksi. */
	private static final Set<String> ERLAUBT = erlaubte();

	/** Wohin die Zeile geht. Der Assistent haengt mit tail -F an dieser Datei. */
	private static final Path DATEI = Paths.get(
			System.getenv().getOrDefault("KLINGEL_DATEI",
					System.getProperty("user.home") + "/lost/klingel.log"));

	/**
	 * Entprellung. Jede Zeile kostet Platz im Gespraech des Assistenten — und
	 * Platz dort ist knapp. Wer in einem Zug fuenf Nachrichten schickt, soll
	 * einmal klingeln, nicht fuenfmal.
	 */
	private static final long ENTPRELLUNG_MS = 120_000L;

	private static final ConcurrentHashMap<String, Long> ZULETZT = new ConcurrentHashMap<>();

	private static final DateTimeFormatter UHRZEIT = DateTimeFormatter.ofPattern("HH:mm");

	private static Set<String> erlaubte() {
		final String roh = System.getenv().getOrDefault("KLINGEL_ERLAUBT", "362260317071343630");
		return Arrays.stream(roh.split(",")).map(String::trim).filter(s -> !s.isEmpty())
				.collect(Collectors.toUnmodifiableSet());
	}

	static boolean darfKlingeln(String userId) {
		return userId != null && ERLAUBT.contains(userId);
	}

	/**
	 * Kuerzt die Nachricht auf ein Signal.
	 *
	 * Zeilenumbrueche muessen weg: der Beobachter liest zeilenweise, ein
	 * Umbruch im Text waere sonst ein zweites, gefaelschtes Ereignis. Aus
	 * demselben Grund fliegen Steuerzeichen raus.
	 */
	static String kurzfassung(String text) {
		if (text == null) {
			return "(leer)";
		}
		final StringBuilder sb = new StringBuilder(text.length());
		for (final char c : text.toCharArray()) {
			sb.append(Character.isISOControl(c) ? ' ' : c);
		}
		final String sauber = sb.toString().replaceAll("\\s+", " ").trim();
		if (sauber.isEmpty()) {
			return "(ohne Text)";
		}
		return sauber.length() <= 120 ? sauber : sauber.substring(0, 117) + "...";
	}

	/** true, wenn fuer diesen Nutzer gerade geklingelt werden darf. */
	static boolean entprellt(String userId, long jetzt) {
		final Long vorher = ZULETZT.get(userId);
		if (vorher != null && jetzt - vorher < ENTPRELLUNG_MS) {
			return false;
		}
		ZULETZT.put(userId, jetzt);
		return true;
	}

	static String zeile(String wer, String was, LocalTime zeit) {
		return UHRZEIT.format(zeit) + " | DM " + wer + ": " + kurzfassung(was);
	}

	@Override
	public void onMessageReceived(@Nonnull MessageReceivedEvent event) {
		if (event.isFromGuild() || event.getAuthor().isBot()) {
			return;
		}
		final String id = event.getAuthor().getId();
		if (!darfKlingeln(id)) {
			return;
		}
		if (!entprellt(id, System.currentTimeMillis())) {
			return;
		}

		final String wer = event.getAuthor().getEffectiveName();
		try {
			Files.writeString(DATEI, zeile(wer, event.getMessage().getContentDisplay(), LocalTime.now()) + "\n",
					StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException e) {
			System.err.println("Klingel: konnte " + DATEI + " nicht schreiben: " + e.getMessage());
			return;
		}

		// Ohne Rueckmeldung schreibt er ins Leere. Bewusst vage formuliert:
		// laeuft gerade keine Sitzung, liegt die Zeile bis zur naechsten Wache.
		event.getChannel()
				.sendMessage("Angekommen — ich hab's weitergereicht. Antwort kommt hier oder direkt am Issue/PR.")
				.queue(null, fehler -> System.err.println("Klingel: Antwort ging nicht raus: " + fehler.getMessage()));
	}
}
