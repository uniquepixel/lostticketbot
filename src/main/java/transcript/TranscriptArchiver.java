package transcript;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import db.Database;
import lostticketbot.Bot;
import model.Panel;
import model.Ticket;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.utils.FileUpload;

/**
 * Archiviert ein geschlossenes Ticket und schreibt den Log-Eintrag.
 *
 * Der Archiv-Post im Storage-Kanal traegt die Metadaten im Nachrichtentext und
 * den Volltext als JSON-Anhang. Damit ist die Datenbank rekonstruierbar: geht
 * sie verloren, liest ein Durchlauf des Storage-Kanals alles zurueck. Discord
 * ist die Wahrheit, die Datenbank der schnelle Zugriffsweg.
 */
public final class TranscriptArchiver {

	private static final ObjectMapper JSON = new ObjectMapper();

	private TranscriptArchiver() {
	}

	/** Eine Zeile des Verlaufs, so wie sie in der Datenbank steht. */
	private record Row(String messageId, String authorId, String authorName, boolean bot,
			String content, String sentAt, String editedAt, String deletedAt) {
	}

	/**
	 * Liegt fuer dieses Ticket schon ein Archiv-Post im Storage-Kanal?
	 *
	 * Gebraucht beim Loeschen: ein zweites Archivieren wuerde einen zweiten
	 * Log-Eintrag schreiben und im Kanal so aussehen, als waere das Ticket
	 * zweimal geschlossen worden.
	 */
	public static boolean istArchiviert(long ticketId) {
		return Database.count("SELECT COUNT(*) FROM transcript_archives WHERE ticket_id = ?",
				ticketId) > 0;
	}

	public static void archive(Guild guild, Ticket ticket, Panel panel, String closedBy) {
		final List<Row> rows = loadRows(ticket.id());
		final Map<String, Integer> perAuthor = countPerAuthor(rows);

		String archiveMessageId = null;
		final TextChannel storage = storageChannel();
		if (storage != null) {
			archiveMessageId = writeArchive(storage, ticket, panel, closedBy, rows);
		} else {
			System.err.println("Kein Storage-Kanal — Ticket " + ticket.channelName()
					+ " wird nicht archiviert. Der Verlauf steht weiterhin in der Datenbank.");
		}

		postLog(guild, ticket, panel, closedBy, rows.size(), perAuthor, archiveMessageId);

		if (panel.dmOnClose()) {
			// retrieveUserById statt Member: wer den Server verlassen hat, ist
			// kein Mitglied mehr - und gerade dann wird oft geschlossen.
			guild.getJDA().retrieveUserById(ticket.ownerId()).queue(
					user -> util.Dm.send(user, util.MessageUtil.embed("Ticket geschlossen",
							"Dein Ticket **" + ticket.channelName() + "** auf **" + guild.getName()
									+ "** wurde geschlossen.",
							0x1ec45c)),
					err -> System.out.println("Schliess-DM: Nutzer " + ticket.ownerId()
							+ " nicht gefunden."));
		}
	}

	private static TextChannel storageChannel() {
		final String id = Bot.storageChannelId();
		if (id == null || id.isBlank() || Bot.jda() == null) {
			return null;
		}
		return Bot.jda().getTextChannelById(id);
	}

	private static List<Row> loadRows(long ticketId) {
		return Database.query(
				"SELECT message_id, author_id, author_name, author_bot, content, sent_at, edited_at, deleted_at "
						+ "FROM ticket_messages WHERE ticket_id = ? ORDER BY sent_at, id",
				rs -> new Row(
						rs.getString("message_id"),
						rs.getString("author_id"),
						rs.getString("author_name"),
						rs.getBoolean("author_bot"),
						rs.getString("content"),
						String.valueOf(rs.getTimestamp("sent_at")),
						rs.getTimestamp("edited_at") == null ? null : String.valueOf(rs.getTimestamp("edited_at")),
						rs.getTimestamp("deleted_at") == null ? null : String.valueOf(rs.getTimestamp("deleted_at"))),
				ticketId);
	}

	/** Nachrichten je Teilnehmer — Ticket Tool zeigt das im Log als "Users in transcript". */
	private static Map<String, Integer> countPerAuthor(List<Row> rows) {
		final Map<String, Integer> counts = new LinkedHashMap<>();
		for (final Row r : rows) {
			counts.merge(r.authorId(), 1, Integer::sum);
		}
		return counts;
	}

	private static String writeArchive(TextChannel storage, Ticket ticket, Panel panel,
			String closedBy, List<Row> rows) {
		try {
			final ObjectNode root = JSON.createObjectNode();
			root.put("ticket_id", ticket.id());
			root.put("guild_id", ticket.guildId());
			root.put("panel", panel.name());
			root.put("number", ticket.number());
			root.put("channel_id", ticket.channelId());
			root.put("channel_name", ticket.channelName());
			root.put("owner_id", ticket.ownerId());
			root.put("closed_by", closedBy);
			root.put("opened_at", String.valueOf(ticket.openedAt()));

			final ArrayNode messages = root.putArray("messages");
			for (final Row r : rows) {
				final ObjectNode m = messages.addObject();
				m.put("message_id", r.messageId());
				m.put("author_id", r.authorId());
				m.put("author_name", r.authorName());
				m.put("bot", r.bot());
				m.put("content", r.content());
				m.put("sent_at", r.sentAt());
				if (r.editedAt() != null) {
					m.put("edited_at", r.editedAt());
				}
				if (r.deletedAt() != null) {
					m.put("deleted_at", r.deletedAt());
				}
			}

			// Anhaenge sind schon beim Mitschreiben gespiegelt worden; hier
			// kommen nur die Zeiger dazu, damit das Archiv fuer sich steht.
			final ArrayNode attachments = root.putArray("attachments");
			Database.query(
					"SELECT filename, content_type, size_bytes, storage_channel_id, storage_message_id, "
							+ "storage_attach_id FROM ticket_attachments WHERE ticket_id = ? ORDER BY id",
					rs -> {
						final ObjectNode a = attachments.addObject();
						a.put("filename", rs.getString("filename"));
						a.put("content_type", rs.getString("content_type"));
						a.put("size_bytes", rs.getLong("size_bytes"));
						a.put("storage_channel_id", rs.getString("storage_channel_id"));
						a.put("storage_message_id", rs.getString("storage_message_id"));
						a.put("storage_attach_id", rs.getString("storage_attach_id"));
						return null;
					}, ticket.id());

			final byte[] payload = JSON.writerWithDefaultPrettyPrinter()
					.writeValueAsBytes(root);

			// Die Metadaten stehen bewusst auch im Klartext, damit der Kanal
			// ohne Datenbank durchsuchbar bleibt.
			final String header = "**" + ticket.channelName() + "** · Panel `" + panel.name()
					+ "` · Owner <@" + ticket.ownerId() + "> · "
					+ rows.size() + " Nachrichten · Ticket-ID " + ticket.id();

			final Message posted = storage.sendMessage(header)
					.addFiles(FileUpload.fromData(payload,
							"transcript-" + ticket.channelName() + ".json"))
					.complete();

			Database.update(
					"INSERT INTO transcript_archives (ticket_id, storage_channel_id, storage_message_id) "
							+ "VALUES (?, ?, ?) ON CONFLICT (ticket_id) DO UPDATE "
							+ "SET storage_channel_id = EXCLUDED.storage_channel_id, "
							+ "storage_message_id = EXCLUDED.storage_message_id",
					ticket.id(), storage.getId(), posted.getId());

			return posted.getId();
		} catch (final Exception e) {
			System.err.println("Archivierung von Ticket " + ticket.channelName() + " fehlgeschlagen: " + e);
			return null;
		}
	}

	/** Der Log-Eintrag im Kanal des Panels — bewusst nah an dem, was Ticket Tool schreibt. */
	private static void postLog(Guild guild, Ticket ticket, Panel panel, String closedBy,
			int messageCount, Map<String, Integer> perAuthor, String archiveMessageId) {
		if (panel.logChannelId() == null || panel.logChannelId().isBlank()) {
			return;
		}
		final TextChannel log = guild.getTextChannelById(panel.logChannelId());
		if (log == null) {
			System.err.println("Log-Kanal " + panel.logChannelId() + " für Panel " + panel.name()
					+ " gibt es nicht mehr.");
			return;
		}

		final StringBuilder participants = new StringBuilder();
		perAuthor.entrySet().stream()
				.sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
				.limit(10)
				.forEach(e -> participants.append(e.getValue()).append(" · <@").append(e.getKey()).append(">\n"));

		final EmbedBuilder embed = new EmbedBuilder()
				.setColor(new java.awt.Color(0x1ec45c))
				.addField("Ticket", ticket.channelName(), true)
				.addField("Panel", panel.name(), true)
				.addField("Eröffner", "<@" + ticket.ownerId() + ">", true)
				.addField("Geschlossen von", closedBy == null ? "automatisch" : "<@" + closedBy + ">", true)
				.addField("Nachrichten", String.valueOf(messageCount), true);

		if (participants.length() > 0) {
			embed.addField("Beteiligte", participants.toString(), false);
		}
		embed.setFooter(archiveMessageId == null
				? "nicht archiviert — kein Storage-Kanal"
				: "Archiv: " + archiveMessageId);

		// Die lesbare Datei haengt am Log-Eintrag, so wie es Ticket Tool
		// gemacht hat. Die Orga oeffnet den Log-Kanal und klickt die Datei an —
		// diese Gewohnheit soll die Umstellung nicht kosten. Bilder stecken
		// darin, damit die Datei auch in einem Jahr noch vollstaendig ist.
		final byte[] html = htmlErzeugen(ticket, panel);
		if (html == null) {
			log.sendMessageEmbeds(embed.build()).queue();
			return;
		}
		log.sendMessageEmbeds(embed.build())
				.addFiles(FileUpload.fromData(html, "transcript-" + ticket.channelName() + ".html"))
				.queue(ok -> {
				}, err -> {
					// Meist das Uploadlimit. Der Log-Eintrag selbst ist
					// wichtiger als die Datei, also lieber ohne als gar nicht.
					System.err.println("Transcript-Datei für " + ticket.channelName()
							+ " konnte nicht angehängt werden: " + err.getMessage());
					log.sendMessageEmbeds(embed.build()).queue();
				});
	}

	private static byte[] htmlErzeugen(Ticket ticket, Panel panel) {
		try {
			return HtmlRenderer.rendern(ticket, panel, true)
					.getBytes(java.nio.charset.StandardCharsets.UTF_8);
		} catch (final RuntimeException e) {
			System.err.println("Transcript-HTML für " + ticket.channelName()
					+ " fehlgeschlagen: " + e);
			return null;
		}
	}
}
