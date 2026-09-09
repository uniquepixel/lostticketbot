package migration;

import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import db.Database;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;

/**
 * Uebernimmt die von Ticket Tool erzeugten Transcripts.
 *
 * Die HTML-Dateien werden bewusst NICHT neu hochgeladen. Sie liegen bereits als
 * Anhaenge in den Log-Kanaelen und damit ohnehin dauerhaft auf Discord; sie
 * herunterzuladen und wieder hochzuladen waeren rund 15 GB Verkehr ohne jeden
 * Gewinn. Stattdessen merken wir uns, wo sie liegen, und lesen die Metadaten
 * aus den Log-Embeds.
 *
 * Ticket Tools Log-Embed hat eine feste Form:
 *   Ticket Owner       <@123>
 *   Ticket Name        th16-closed-0850
 *   Panel Name         Rh16
 *   Users in transcript ...
 * Der Autor des Embeds ist derjenige, der geschlossen hat.
 */
public final class LegacyImporter {

	/** Ticket Tools Anwendungs-ID. Nur dessen Nachrichten werden ausgewertet. */
	private static final String TICKET_TOOL_ID = "557628352828014614";

	private static final Pattern USER_MENTION = Pattern.compile("<@!?(\\d+)>");

	private LegacyImporter() {
	}

	public record Result(int gesehen, int uebernommen, int uebersprungen) {
	}

	/**
	 * Liest den Kanal vollstaendig durch und legt fuer jedes Transcript einen
	 * Zeiger an. Mehrfach ausfuehrbar: bereits bekannte Log-Nachrichten werden
	 * uebersprungen, es entstehen keine Duplikate.
	 *
	 * Laeuft blockierend und gehoert deshalb in einen eigenen Thread.
	 */
	public static Result importChannel(TextChannel log, Consumer<Integer> progress) {
		int seen = 0;
		int imported = 0;
		int skipped = 0;

		for (final Message message : log.getIterableHistory().cache(false)) {
			seen++;
			if (progress != null && seen % 250 == 0) {
				progress.accept(seen);
			}

			if (!TICKET_TOOL_ID.equals(message.getAuthor().getId()) || message.getAttachments().isEmpty()) {
				skipped++;
				continue;
			}
			final Message.Attachment file = message.getAttachments().get(0);
			final MessageEmbed embed = message.getEmbeds().isEmpty() ? null : message.getEmbeds().get(0);

			final String ticketName = field(embed, "Ticket Name");
			final String panelName = field(embed, "Panel Name");
			final String ownerId = firstMention(field(embed, "Ticket Owner"));
			final String closedBy = embed == null || embed.getAuthor() == null
					? null
					: embed.getAuthor().getName();

			final int written = Database.update(
					"INSERT INTO legacy_transcripts (guild_id, log_channel_id, log_message_id, attachment_id, "
							+ "filename, size_bytes, ticket_name, panel_name, owner_id, closed_by, closed_at) "
							+ "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
							+ "ON CONFLICT (log_message_id) DO NOTHING",
					log.getGuild().getId(), log.getId(), message.getId(), file.getId(),
					file.getFileName(), (long) file.getSize(), ticketName, panelName, ownerId, closedBy,
					java.sql.Timestamp.from(message.getTimeCreated().toInstant()));

			if (written > 0) {
				imported++;
			} else {
				skipped++;
			}
		}
		return new Result(seen, imported, skipped);
	}

	private static String field(MessageEmbed embed, String name) {
		if (embed == null) {
			return null;
		}
		final List<MessageEmbed.Field> fields = embed.getFields();
		for (final MessageEmbed.Field f : fields) {
			if (name.equalsIgnoreCase(f.getName())) {
				return f.getValue();
			}
		}
		return null;
	}

	private static String firstMention(String raw) {
		if (raw == null) {
			return null;
		}
		final Matcher m = USER_MENTION.matcher(raw);
		return m.find() ? m.group(1) : null;
	}
}
