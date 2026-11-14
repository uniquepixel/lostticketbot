package ticket;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import model.Frage;
import model.Panel;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.text.TextInput;
import net.dv8tion.jda.api.interactions.components.text.TextInputStyle;
import net.dv8tion.jda.api.interactions.modals.Modal;

/**
 * Das Formular, das vor dem Öffnen eines Tickets erscheint, wenn das Panel
 * Fragen hat.
 *
 * Die Antworten gehen als gewöhnliche Nachricht ins Ticket und nicht als
 * Embed: der Mitschnitt speichert den Nachrichtentext, und die Angaben eines
 * Bewerbers gehören ins Archiv. Gepingt wird dabei niemand — es gilt die stille
 * Voreinstellung des Bots, auch wenn jemand „@everyone“ ins Feld schreibt.
 */
public final class Formular {

	public static final String PREFIX = "tb:form:";
	/** Discords Nachrichtenlimit ist 2000; etwas Luft für Kopf und Zitatzeichen. */
	static final int BLOCK = 1900;

	private Formular() {
	}

	public static Modal bauen(Panel panel, List<Frage> fragen) {
		final String titel = panel.welcomeEmbedTitle() != null && !panel.welcomeEmbedTitle().isBlank()
				? panel.welcomeEmbedTitle()
				: panel.name();
		final List<ActionRow> zeilen = new ArrayList<>();
		for (int i = 0; i < fragen.size() && i < 5; i++) {
			final Frage f = fragen.get(i);
			final TextInput.Builder b = TextInput.create(feldId(i), f.label(),
					f.langeAntwort() ? TextInputStyle.PARAGRAPH : TextInputStyle.SHORT)
					.setRequired(f.pflicht())
					.setMaxLength(f.maxLaenge() == null ? 4000 : f.maxLaenge());
			if (f.placeholder() != null && !f.placeholder().isBlank()) {
				b.setPlaceholder(f.placeholder());
			}
			zeilen.add(ActionRow.of(b.build()));
		}
		return Modal.create(PREFIX + panel.id(), kuerzen(titel, 45)).addComponents(zeilen).build();
	}

	public static String feldId(int index) {
		return "f" + index;
	}

	/**
	 * Baut die Nachricht(en) mit den Antworten, je höchstens {@link #BLOCK}
	 * Zeichen. Eine einzelne lange Antwort wird an Zeilen- oder Wortgrenzen
	 * geteilt, nie abgeschnitten.
	 */
	public static List<String> antwortNachrichten(List<Frage> fragen, Map<String, String> antworten) {
		final List<String> bloecke = new ArrayList<>();
		for (int i = 0; i < fragen.size(); i++) {
			final String a = antworten.get(feldId(i));
			final String antwort = a == null || a.isBlank() ? "—" : a.strip();
			final StringBuilder sb = new StringBuilder("**").append(fragen.get(i).label()).append("**\n");
			for (final String zeile : antwort.split("\n", -1)) {
				sb.append("> ").append(zeile).append('\n');
			}
			bloecke.add(sb.toString());
		}

		final List<String> nachrichten = new ArrayList<>();
		StringBuilder aktuell = new StringBuilder("📝 **Angaben aus dem Formular**\n\n");
		for (final String block : bloecke) {
			for (final String teil : teilen(block, BLOCK)) {
				if (aktuell.length() + teil.length() + 1 > BLOCK) {
					nachrichten.add(aktuell.toString().stripTrailing());
					aktuell = new StringBuilder();
				}
				aktuell.append(teil).append('\n');
			}
		}
		if (!aktuell.toString().isBlank()) {
			nachrichten.add(aktuell.toString().stripTrailing());
		}
		return nachrichten;
	}

	static List<String> teilen(String text, int max) {
		final List<String> teile = new ArrayList<>();
		String rest = text;
		while (rest.length() > max) {
			int schnitt = rest.lastIndexOf('\n', max);
			if (schnitt < max / 2) {
				schnitt = rest.lastIndexOf(' ', max);
			}
			if (schnitt < max / 2) {
				schnitt = max;
			}
			teile.add(rest.substring(0, schnitt));
			rest = rest.substring(schnitt).stripLeading();
			if (!rest.isEmpty() && !rest.startsWith(">")) {
				rest = "> " + rest;
			}
		}
		teile.add(rest);
		return teile;
	}

	private static String kuerzen(String s, int max) {
		return s.length() <= max ? s : s.substring(0, max - 1) + "…";
	}
}
