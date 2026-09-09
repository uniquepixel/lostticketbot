package transcript;

import java.nio.charset.StandardCharsets;

import db.Database;
import db.PanelDao;
import model.Panel;
import model.Ticket;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.utils.FileUpload;
import ticket.Visibility;
import util.MessageUtil;

/**
 * Schickt den Verlauf eines Tickets als Datei an den Fragenden.
 *
 * Eine Stelle fuer beide Wege — den Befehl {@code /transcript} und den Knopf
 * unter der Schliessnachricht. Zwei Implementierungen waeren genau die Art von
 * Doppelung, bei der die eine irgendwann die Sichtbarkeitspruefung verliert.
 *
 * Die Antwort ist immer nur fuer den Aufrufenden sichtbar: ein Transcript
 * enthaelt Screenshots und Gespraeche, die nicht in einen offenen Kanal
 * gehoeren.
 */
public final class TranscriptAusgabe {

	private TranscriptAusgabe() {
	}

	public static void senden(InteractionHook hook, Guild guild, Ticket ticket, String anfragenderId) {
		if (!Visibility.darfSehen(guild, ticket, anfragenderId)) {
			// Dieselbe Meldung wie bei einer unbekannten ID: wer ein Ticket
			// nicht sehen darf, soll nicht durch Ausprobieren erfahren, dass es
			// existiert.
			hook.editOriginalEmbeds(MessageUtil.error("Kein Ticket mit dieser ID gefunden.")).queue();
			return;
		}

		final Panel panel = ticket.panelId() == null
				? null
				: PanelDao.byId(ticket.panelId()).orElse(null);

		final byte[] html = HtmlRenderer.rendern(ticket, panel).getBytes(StandardCharsets.UTF_8);
		final int nachrichten = Database.count(
				"SELECT COUNT(*) FROM ticket_messages WHERE ticket_id = ?", ticket.id());
		final int anhaenge = Database.count(
				"SELECT COUNT(*) FROM ticket_attachments WHERE ticket_id = ?", ticket.id());

		hook.editOriginalEmbeds(MessageUtil.embed("Transcript " + ticket.channelName(),
				nachrichten + " Nachrichten, " + anhaenge + " Anhänge · "
						+ (html.length / 1024) + " KB\n\n"
						+ "Herunterladen und im Browser öffnen. Die Bilder sind verlinkt, nicht "
						+ "eingebettet — deshalb ist die Datei klein, und deshalb zeigt sie die "
						+ "Bilder nach etwa einem Tag nicht mehr an. Die Bilder selbst bleiben "
						+ "gespeichert; ein neues Transcript erzeugt frische Links.",
				0x1ec45c))
				.setFiles(FileUpload.fromData(html, "transcript-" + ticket.channelName() + ".html"))
				.queue();
	}
}
