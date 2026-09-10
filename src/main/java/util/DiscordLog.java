package util;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;

/**
 * Spiegelt die Konsolenausgabe in einen Discord-Kanal.
 *
 * Dasselbe Prinzip wie beim lostmanager, aber an drei Stellen anders:
 *
 * <ul>
 * <li><b>Zeilenweise statt byteweise.</b> Die Vorlage schiebt jedes einzelne
 * Byte in die Warteschlange und macht daraus ein {@code char} — bei einem
 * Umlaut, der in UTF-8 aus zwei Bytes besteht, kommt so Buchstabensalat
 * heraus. Hier werden Bytes bis zum Zeilenumbruch gesammelt und dann als
 * Ganzes dekodiert; ein Zeilenumbruch kann nie mitten in einem Zeichen
 * stehen.</li>
 * <li><b>Begrenzte Warteschlange.</b> Kommt der Kanal nicht hinterher, gehen
 * Zeilen verloren statt Arbeitsspeicher. Ein Log ist Beiwerk; der Bot ist es
 * nicht.</li>
 * <li><b>Selbstabschaltung.</b> Scheitert das Senden mehrfach hintereinander,
 * hoert der Spiegel auf. Sonst protokolliert JDA den Fehlschlag, der Fehlschlag
 * landet in der Warteschlange, und das Ganze dreht sich im Kreis.</li>
 * </ul>
 *
 * Der Kanal kommt aus {@code TICKETBOT_LOG_CHANNEL_ID}. Ohne ihn passiert
 * nichts — die Ausgabe geht dann nur nach journald, wie vorher.
 */
public final class DiscordLog {

	/** Discords Nachrichtenlimit ist 2000; der Code-Block braucht auch Platz. */
	static final int BLOCKGROESSE = 1900;

	private static final String THREAD = "discord-log";
	private static final int MAX_FEHLER = 5;

	/** So viel Text wird hoechstens gehalten, solange JDA noch nicht bereit ist. */
	private static final int HALTEGRENZE = 100_000;

	private static final BlockingQueue<String> zeilen = new ArrayBlockingQueue<>(2000);
	private static final AtomicInteger fehlerInFolge = new AtomicInteger();
	private static PrintStream originalOut;
	private static PrintStream originalErr;
	private static volatile JDA jda;
	private static volatile String kanalId;
	private static volatile boolean an;

	private DiscordLog() {
	}

	/**
	 * Haengt sich in System.out und System.err ein.
	 *
	 * Muss vor allem anderen laufen, damit auch die Startmeldungen mitgehen —
	 * die Warteschlange puffert, bis JDA bereit ist.
	 */
	public static void setup(String logKanalId) {
		if (logKanalId == null || logKanalId.isBlank()) {
			return;
		}
		kanalId = logKanalId;
		originalOut = System.out;
		originalErr = System.err;
		an = true;

		System.setOut(new PrintStream(new Abzweig(originalOut), true, StandardCharsets.UTF_8));
		System.setErr(new PrintStream(new Abzweig(originalErr), true, StandardCharsets.UTF_8));

		final Thread t = new Thread(DiscordLog::schleife, THREAD);
		t.setDaemon(true);
		t.start();
	}

	public static void setJda(JDA jdaInstanz) {
		jda = jdaInstanz;
	}

	// -----------------------------------------------------------------------

	/** Schreibt durch und sammelt nebenbei ganze Zeilen ein. */
	private static final class Abzweig extends OutputStream {

		private final OutputStream original;
		private final ByteArrayOutputStream zeile = new ByteArrayOutputStream(256);

		Abzweig(OutputStream original) {
			this.original = original;
		}

		@Override
		public synchronized void write(int b) throws java.io.IOException {
			original.write(b);
			sammle(b);
		}

		@Override
		public synchronized void write(byte[] b, int off, int len) throws java.io.IOException {
			original.write(b, off, len);
			for (int i = off; i < off + len; i++) {
				sammle(b[i]);
			}
		}

		@Override
		public void flush() throws java.io.IOException {
			original.flush();
		}

		private void sammle(int b) {
			if (b == '\n') {
				fertig();
			} else if (b != '\r') {
				zeile.write(b);
			}
		}

		private void fertig() {
			final String text = zeile.toString(StandardCharsets.UTF_8);
			zeile.reset();
			// Nicht aus dem eigenen Sendethread mitschreiben, sonst erzeugt
			// jede Meldung ueber das Senden die naechste Meldung.
			if (an && !text.isBlank() && !THREAD.equals(Thread.currentThread().getName())) {
				zeilen.offer(text);
			}
		}
	}

	private static void schleife() {
		final List<String> puffer = new ArrayList<>();
		int laenge = 0;
		long letzterVersand = System.currentTimeMillis();

		while (an) {
			try {
				final String z = zeilen.poll(1, TimeUnit.SECONDS);
				if (z != null) {
					puffer.add(z);
					laenge += z.length() + 1;
				}

				final long jetzt = System.currentTimeMillis();
				final boolean voll = laenge >= BLOCKGROESSE;
				// Kurz sammeln, bevor gesendet wird: ein Start erzeugt ein
				// Dutzend Zeilen, und Discord laesst nur wenige Nachrichten je
				// Sekunde zu.
				final boolean reif = !puffer.isEmpty() && jetzt - letzterVersand > 2000;

				// Solange JDA nicht bereit ist, wird gehalten statt geleert.
				// Sonst verschwinden ausgerechnet die Startmeldungen — die,
				// wegen derer man nach einem Neustart hineinschaut.
				if (!sendenJetzt(jda != null, voll, reif)) {
					if (jda == null && laenge > HALTEGRENZE) {
						// Notbremse: kommt JDA nie hoch, soll der Puffer nicht
						// unbegrenzt wachsen.
						puffer.clear();
						laenge = 0;
					}
					continue;
				}

				{
					for (final String block : bloecke(puffer)) {
						senden(block);
					}
					puffer.clear();
					laenge = 0;
					letzterVersand = jetzt;
				}
			} catch (final InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			} catch (final RuntimeException e) {
				originalErr.println("Discord-Log: " + e);
			}
		}
	}

	/**
	 * Wird jetzt gesendet?
	 *
	 * Eigene Methode, weil die erste Bedingung leicht zu uebersehen ist: ohne
	 * bereite JDA-Verbindung wird NICHT gesendet — und auch nicht geleert.
	 */
	static boolean sendenJetzt(boolean jdaDa, boolean voll, boolean reif) {
		return jdaDa && (voll || reif);
	}

	/**
	 * Fasst Zeilen zu Bloecken zusammen, die in eine Discord-Nachricht passen.
	 *
	 * Eine ueberlange Einzelzeile — etwa ein Stacktrace ohne Umbrueche — wird
	 * hart geschnitten, statt die ganze Nachricht scheitern zu lassen.
	 */
	static List<String> bloecke(List<String> eingang) {
		final List<String> aus = new ArrayList<>();
		final StringBuilder aktuell = new StringBuilder();

		for (final String roh : eingang) {
			for (final String stueck : zerlegen(roh)) {
				if (aktuell.length() + stueck.length() + 1 > BLOCKGROESSE && aktuell.length() > 0) {
					aus.add(aktuell.toString());
					aktuell.setLength(0);
				}
				if (aktuell.length() > 0) {
					aktuell.append('\n');
				}
				aktuell.append(stueck);
			}
		}
		if (aktuell.length() > 0) {
			aus.add(aktuell.toString());
		}
		return aus;
	}

	private static List<String> zerlegen(String zeile) {
		if (zeile.length() <= BLOCKGROESSE) {
			return List.of(zeile);
		}
		final List<String> teile = new ArrayList<>();
		for (int i = 0; i < zeile.length(); i += BLOCKGROESSE) {
			teile.add(zeile.substring(i, Math.min(zeile.length(), i + BLOCKGROESSE)));
		}
		return teile;
	}

	private static void senden(String block) {
		final JDA aktuelle = jda;
		if (aktuelle == null || block.isBlank()) {
			return;
		}
		final TextChannel kanal = aktuelle.getTextChannelById(kanalId);
		if (kanal == null) {
			// Nicht sofort aufgeben. Direkt nach einem Verbindungsabbruch ist
			// der Kanalcache kurz leer, und ein einzelner Treffer in diese
			// Luecke hat den Spiegel frueher fuer den Rest der Laufzeit
			// abgeschaltet — genau das ist am 10.09.2026 passiert.
			scheitern("Kanal " + kanalId + " (noch) nicht im Cache");
			return;
		}

		// Mit Fehlerbehandlung, damit JDA den Fehlschlag NICHT selbst
		// protokolliert — sonst faende er den Weg zurueck in diese
		// Warteschlange und das Ganze drehte sich im Kreis.
		kanal.sendMessage("```\n" + block + "\n```").queue(
				ok -> fehlerInFolge.set(0),
				err -> scheitern("Senden fehlgeschlagen: " + err.getMessage()));
	}

	/**
	 * Zaehlt einen Fehlschlag und schaltet erst nach mehreren ab.
	 *
	 * Ein Log ist Beiwerk: es darf ein paar Zeilen verlieren, aber es soll
	 * nicht wegen einer einzigen Unpaesslichkeit fuer immer verstummen — und
	 * schon gar nicht, ohne dass das irgendwo steht.
	 */
	private static void scheitern(String grund) {
		originalErr.println("Discord-Log: " + grund);
		if (fehlerInFolge.incrementAndGet() >= MAX_FEHLER) {
			originalErr.println("Discord-Log: " + MAX_FEHLER
					+ "-mal hintereinander gescheitert — Spiegel aus.");
			an = false;
		}
	}

	// -- nur fuer Tests ------------------------------------------------------

	static void fuerTestZuruecksetzen() {
		fehlerInFolge.set(0);
		an = true;
		originalErr = System.err;
	}

	static boolean istAn() {
		return an;
	}

	static void fuerTestScheitern(String grund) {
		scheitern(grund);
	}

	static void fuerTestErfolg() {
		fehlerInFolge.set(0);
	}
}
