package transcript;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.annotation.Nonnull;

import db.Database;
import lostticketbot.Bot;
import model.Ticket;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Message.Attachment;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.message.MessageDeleteEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.MessageUpdateEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.utils.FileUpload;
import db.TicketDao;

/**
 * Schreibt den Verlauf offener Tickets laufend mit.
 *
 * Warum laufend und nicht erst beim Schliessen, wie Ticket Tool es macht:
 * Discords Anhang-URLs sind signiert und laufen nach etwa einem Tag ab. Wer den
 * Verlauf erst beim Schliessen aus der Kanalhistorie holt, hat bei einem zwei
 * Wochen alten Ticket tote Bilder. Wir spiegeln Anhaenge deshalb sofort in
 * einen eigenen Storage-Kanal und merken uns nur, wo sie liegen.
 *
 * Nebeneffekt: geloeschte und bearbeitete Nachrichten bleiben nachvollziehbar.
 * In einem Beschwerde-Ticket ist das eher Feature als Problem.
 */
public class TranscriptRecorder extends ListenerAdapter {

	/**
	 * Anhaenge werden ausserhalb des Event-Threads gespiegelt - Herunterladen
	 * und wieder Hochladen dauert, und der Gateway-Thread darf dabei nicht
	 * warten. Klein gehalten, damit ein Bilderschwall die Maschine nicht
	 * belegt; die Warteschlange laeuft dann eben etwas hinterher.
	 */
	private static final ExecutorService MIRROR = Executors.newFixedThreadPool(2, r -> {
		final Thread t = new Thread(r, "attachment-mirror");
		t.setDaemon(true);
		return t;
	});

	@Override
	public void onMessageReceived(@Nonnull MessageReceivedEvent event) {
		if (!event.isFromGuild()) {
			return;
		}
		final Optional<Ticket> ticket = TicketDao.byChannel(event.getChannel().getId());
		if (ticket.isEmpty()) {
			return;
		}

		final Message message = event.getMessage();
		final long ticketId = ticket.get().id();

		final long rowId;
		try {
			rowId = Database.insertIgnoringConflict(
					"INSERT INTO ticket_messages "
							+ "(ticket_id, message_id, author_id, author_name, author_bot, content, sent_at) "
							+ "VALUES (?, ?, ?, ?, ?, ?, ?) "
							+ "ON CONFLICT (ticket_id, message_id) DO NOTHING",
					ticketId,
					message.getId(),
					message.getAuthor().getId(),
					message.getAuthor().getName(),
					message.getAuthor().isBot(),
					message.getContentRaw(),
					java.sql.Timestamp.from(message.getTimeCreated().toInstant()));
		} catch (final RuntimeException e) {
			System.err.println("Transcript-Zeile konnte nicht geschrieben werden: " + e.getMessage());
			return;
		}

		// 0 heisst: die Nachricht war schon erfasst (Wiederverbindung des
		// Gateways kann eine Nachricht erneut liefern). Dann auch die Anhaenge
		// nicht ein zweites Mal spiegeln.
		if (rowId == 0L) {
			return;
		}

		if (!message.getAttachments().isEmpty()) {
			MIRROR.submit(() -> mirrorAttachments(ticketId, rowId, message.getAttachments()));
		}
	}

	@Override
	public void onMessageUpdate(@Nonnull MessageUpdateEvent event) {
		if (!event.isFromGuild() || TicketDao.byChannel(event.getChannel().getId()).isEmpty()) {
			return;
		}
		Database.update(
				"UPDATE ticket_messages SET content = ?, edited_at = CURRENT_TIMESTAMP WHERE message_id = ?",
				event.getMessage().getContentRaw(), event.getMessageId());
	}

	@Override
	public void onMessageDelete(@Nonnull MessageDeleteEvent event) {
		if (!event.isFromGuild() || TicketDao.byChannel(event.getChannel().getId()).isEmpty()) {
			return;
		}
		// Der Inhalt bleibt stehen, nur der Zeitpunkt der Loeschung kommt dazu.
		Database.update(
				"UPDATE ticket_messages SET deleted_at = CURRENT_TIMESTAMP WHERE message_id = ?",
				event.getMessageId());
	}

	/**
	 * Laedt jeden Anhang herunter und legt ihn unveraendert im Storage-Kanal ab.
	 *
	 * Bewusst ohne Umkodierung: Speicher ist bei diesem Ansatz unbegrenzt und
	 * kostenlos, eine Bildpipeline wuerde also nur ein Problem loesen, das es
	 * nicht gibt - und dabei Qualitaet kosten und eine Abhaengigkeit einbringen.
	 */
	private void mirrorAttachments(long ticketId, long messageRowId, List<Attachment> attachments) {
		final String storageChannelId = Bot.storageChannelId();
		if (storageChannelId == null || storageChannelId.isBlank()) {
			System.err.println("Kein Storage-Kanal gesetzt - " + attachments.size()
					+ " Anhang/Anhaenge aus Ticket " + ticketId + " werden nicht gesichert.");
			return;
		}
		final TextChannel storage = Bot.jda().getTextChannelById(storageChannelId);
		if (storage == null) {
			System.err.println("Storage-Kanal " + storageChannelId + " nicht erreichbar.");
			return;
		}

		for (final Attachment attachment : attachments) {
			try (InputStream in = attachment.getProxy().download().join()) {
				final Message stored = storage
						.sendMessage("Ticket " + ticketId + " · " + attachment.getFileName())
						.addFiles(FileUpload.fromData(in, attachment.getFileName()))
						.complete();

				final var storedAttachment = stored.getAttachments().isEmpty()
						? null
						: stored.getAttachments().get(0);
				if (storedAttachment == null) {
					System.err.println("Anhang " + attachment.getFileName() + " kam im Storage nicht an.");
					continue;
				}

				Database.update(
						"INSERT INTO ticket_attachments (ticket_id, ticket_message_id, filename, content_type, "
								+ "size_bytes, storage_channel_id, storage_message_id, storage_attach_id) "
								+ "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
						ticketId, messageRowId, attachment.getFileName(), attachment.getContentType(),
						(long) attachment.getSize(), storage.getId(), stored.getId(), storedAttachment.getId());
			} catch (final Exception e) {
				// Ein verlorener Anhang darf den Ticketbetrieb nicht stoeren -
				// laut loggen, weitermachen.
				System.err.println("Anhang '" + attachment.getFileName() + "' aus Ticket " + ticketId
						+ " konnte nicht gesichert werden: " + e);
			}
		}
	}
}
