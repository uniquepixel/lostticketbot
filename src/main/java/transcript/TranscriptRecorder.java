package transcript;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.annotation.Nonnull;

import db.Database;
import lostticketbot.Bot;
import model.Ticket;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Message.Attachment;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.message.MessageDeleteEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.MessageUpdateEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.events.session.SessionRecreateEvent;
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

	/**
	 * Eigener Faden fuer das Nachholen: es liest je Ticketkanal den ganzen
	 * Verlauf und hat im Gateway-Thread nichts verloren.
	 */
	private static final ExecutorService NACHHOLEN = Executors.newSingleThreadExecutor(r -> {
		final Thread t = new Thread(r, "verlauf-nachholen");
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
		erfasse(ticket.get().id(), event.getMessage());
	}

	/**
	 * Schreibt eine Nachricht in den Verlauf und spiegelt ihre Anhaenge.
	 *
	 * @return true, wenn die Nachricht neu war
	 */
	boolean erfasse(long ticketId, Message message) {
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
			return false;
		}

		// 0 heisst: die Nachricht war schon erfasst (Wiederverbindung des
		// Gateways kann eine Nachricht erneut liefern, und das Nachholen laeuft
		// neben dem Betrieb). Dann auch die Anhaenge nicht ein zweites Mal
		// spiegeln.
		if (rowId == 0L) {
			return false;
		}

		final int attachmentCount = message.getAttachments().size();
		if (attachmentCount > 0) {
			System.out.println("Ticket " + ticketId + ": " + attachmentCount
					+ " Anhang/Anhaenge werden gespiegelt.");
			// execute() statt submit(): submit() faengt jede Ausnahme im Future
			// ab, und wenn niemand den abfragt, verschwindet der Fehler spurlos.
			// Genau das hat den ersten fehlgeschlagenen Spiegelversuch verborgen.
			MIRROR.execute(() -> mirrorAttachments(ticketId, rowId, message.getAttachments()));
		}
		return true;
	}

	// -----------------------------------------------------------------------
	// Nachholen, was geschrieben wurde, waehrend der Bot nicht zuhoerte
	// -----------------------------------------------------------------------

	/**
	 * Beim Start: jeden Ticketkanal gegen die Datenbank abgleichen.
	 *
	 * Anlass: in der Nacht zum 19.11.2026 stand der Server elf Stunden. Was in
	 * dieser Zeit in Tickets geschrieben wurde, kam nie als Event an und fehlte
	 * danach im Verlauf - und damit spaeter im Archiv, das nur aus der
	 * Datenbank gebaut wird. Dasselbe gilt im Kleinen fuer jeden Neustart und
	 * fuer uebernommene Tickets, deren Verlauf vor der Uebernahme liegt.
	 */
	@Override
	public void onReady(@Nonnull ReadyEvent event) {
		nachholenAnstossen(event.getJDA());
	}

	/**
	 * Baut das Gateway die Sitzung neu auf (statt sie fortzusetzen), liefert
	 * Discord die Events der Luecke nicht nach.
	 */
	@Override
	public void onSessionRecreate(@Nonnull SessionRecreateEvent event) {
		nachholenAnstossen(event.getJDA());
	}

	private void nachholenAnstossen(JDA jda) {
		NACHHOLEN.execute(() -> {
			for (final Guild guild : jda.getGuilds()) {
				try {
					nachholen(guild);
				} catch (final RuntimeException e) {
					System.err.println("Verlauf nachholen auf " + guild.getName() + " gescheitert: " + e);
				}
			}
		});
	}

	/**
	 * Traegt fuer jedes nicht geloeschte Ticket des Servers nach, was im Kanal
	 * steht und in der Datenbank fehlt. Je Server, damit ein Test nur den
	 * Testserver durchgehen kann.
	 *
	 * Nur Fehlendes: Bearbeitungen und Loeschungen aus der Luecke bleiben
	 * unbemerkt, der bereits erfasste Text wird nie ueberschrieben.
	 *
	 * @return Zahl der nachgetragenen Nachrichten
	 */
	int nachholen(Guild guild) {
		int gesamt = 0;
		for (final Ticket ticket : TicketDao.nichtGeloescht(guild.getId())) {
			final TextChannel kanal = guild.getTextChannelById(ticket.channelId());
			if (kanal == null) {
				// Kanal weg: das ist Sache des TicketKanalWaechters.
				continue;
			}
			try {
				final Set<String> bekannt = new HashSet<>(Database.query(
						"SELECT message_id FROM ticket_messages WHERE ticket_id = ?",
						rs -> rs.getString(1), ticket.id()));
				final List<Message> fehlend = new ArrayList<>();
				for (final Message message : kanal.getIterableHistory()) {
					if (!bekannt.contains(message.getId())) {
						fehlend.add(message);
					}
				}
				// Der Verlauf kommt neueste zuerst; eingetragen wird in der
				// Reihenfolge, in der geschrieben wurde.
				Collections.reverse(fehlend);
				int neu = 0;
				for (final Message message : fehlend) {
					if (erfasse(ticket.id(), message)) {
						neu++;
					}
				}
				if (neu > 0) {
					System.out.println("Ticket " + ticket.id() + " (" + ticket.channelName() + "): "
							+ neu + " Nachricht(en) nachgetragen, die der Bot nicht mitbekommen hatte.");
				}
				gesamt += neu;
			} catch (final RuntimeException e) {
				System.err.println("Verlauf von Ticket " + ticket.id() + " nicht nachholbar: " + e);
			}
		}
		return gesamt;
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
			try {
				// Herunterladen und Hochladen zusammen wiederholen: ein Aussetzer
				// mittendrin laesst sonst einen halb gelesenen Strom zurueck. Die
				// Datenbankzeile kommt erst danach, damit ein Fehler dort keinen
				// zweiten Storage-Post ausloest.
				final Message stored = mitWiederholung(
						"Anhang '" + attachment.getFileName() + "' aus Ticket " + ticketId,
						WIEDERHOLUNG_PAUSEN_MS, () -> {
							try (InputStream in = attachment.getProxy().download().join()) {
								return storage
										.sendMessage("Ticket " + ticketId + " · " + attachment.getFileName())
										.addFiles(FileUpload.fromData(in, attachment.getFileName()))
										.complete();
							}
						});

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

	/**
	 * Pausen zwischen den Versuchen beim Spiegeln eines Anhangs. Anlass: drei
	 * Anhaenge (Tickets 319, 427, 460) gingen zwischen September und November
	 * an je einem einzelnen Netzaussetzer verloren (Timeout, Stream-Reset).
	 * Die signierte Adresse gilt noch rund einen Tag - ein zweiter Versuch
	 * Sekunden spaeter haette jeden davon gerettet.
	 */
	static final long[] WIEDERHOLUNG_PAUSEN_MS = { 5_000, 30_000 };

	/**
	 * Fuehrt {@code aufgabe} aus und wiederholt sie nach jeder Pause in
	 * {@code pausenMs}, solange sie scheitert. Insgesamt also hoechstens
	 * {@code pausenMs.length + 1} Versuche; der letzte Fehler wird geworfen.
	 * Ein {@code Error} wird nicht wiederholt (siehe CLAUDE.md: Error ist keine
	 * Exception) und geht unveraendert durch.
	 */
	static <T> T mitWiederholung(String was, long[] pausenMs, Callable<T> aufgabe) throws Exception {
		for (int versuch = 0;; versuch++) {
			try {
				return aufgabe.call();
			} catch (final Exception e) {
				if (versuch >= pausenMs.length) {
					throw e;
				}
				System.err.println(was + ": Versuch " + (versuch + 1) + " gescheitert (" + e
						+ "), neuer Versuch in " + (pausenMs[versuch] / 1000) + " s.");
				Thread.sleep(pausenMs[versuch]);
			}
		}
	}
}
