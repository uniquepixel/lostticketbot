package commands;

import javax.annotation.Nonnull;

import db.Database;
import migration.LegacyImporter;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import util.MessageUtil;

/**
 * Uebernahme der Alt-Transcripts aus Ticket Tool.
 *
 * Bewusst als Befehl und nicht als Startvorgang: der Import laeuft ueber die
 * gesamte Historie eines Log-Kanals — beim Bewerbungsserver ueber 4300
 * Nachrichten — und soll bewusst angestossen und beobachtet werden, nicht bei
 * jedem Neustart nebenbei mitlaufen.
 */
public class LegacyCommand extends ListenerAdapter {

	public static final String NAME = "altdaten";

	@Override
	public void onSlashCommandInteraction(@Nonnull SlashCommandInteractionEvent event) {
		if (!NAME.equals(event.getName()) || event.getGuild() == null) {
			return;
		}

		switch (String.valueOf(event.getSubcommandName())) {
			case "importieren" -> importChannel(event);
			case "stand" -> status(event);
			default -> event.reply("Unbekannter Unterbefehl.").setEphemeral(true).queue();
		}
	}

	private void importChannel(SlashCommandInteractionEvent event) {
		final var option = event.getOption("kanal");
		if (option == null || !(option.getAsChannel() instanceof TextChannel log)) {
			event.replyEmbeds(MessageUtil.error("Das muss ein Textkanal sein.")).setEphemeral(true).queue();
			return;
		}

		event.deferReply(true).queue(hook -> new Thread(() -> {
			hook.editOriginalEmbeds(MessageUtil.embed("Import läuft",
					"Lese " + log.getAsMention() + " durch. Bei mehreren tausend Nachrichten dauert das "
							+ "einige Minuten — Discord gibt die Historie nur in Hundertergruppen heraus.",
					0x1ec45c)).queue();

			try {
				final LegacyImporter.Result result = LegacyImporter.importChannel(log,
						seen -> System.out.println("Altdaten-Import " + log.getName() + ": " + seen
								+ " Nachrichten gelesen"));

				hook.editOriginalEmbeds(MessageUtil.embed("Import fertig",
						("""
								**Gelesen:** %d Nachrichten
								**Übernommen:** %d Transcripts
								**Übersprungen:** %d (keine Transcripts oder schon bekannt)

								Die HTML-Dateien bleiben, wo sie sind — übernommen wurde nur der Verweis \
								darauf samt Ticketname, Panel und Eröffner.""")
								.formatted(result.gesehen(), result.uebernommen(), result.uebersprungen()),
						0x1ec45c)).queue();
			} catch (final RuntimeException e) {
				System.err.println("Altdaten-Import fehlgeschlagen: " + e);
				hook.editOriginalEmbeds(MessageUtil.error("Der Import ist abgebrochen: " + e.getMessage()))
						.queue();
			}
		}, "legacy-import").start());
	}

	private void status(SlashCommandInteractionEvent event) {
		final String guildId = event.getGuild().getId();
		final int total = Database.count("SELECT COUNT(*) FROM legacy_transcripts WHERE guild_id = ?", guildId);
		if (total == 0) {
			event.replyEmbeds(MessageUtil.embed("Altdaten",
					"Für diesen Server sind noch keine Alt-Transcripts übernommen.", 0x1ec45c))
					.setEphemeral(true).queue();
			return;
		}

		final StringBuilder sb = new StringBuilder("**" + total + " Transcripts** übernommen.\n\n");
		Database.query(
				"SELECT coalesce(panel_name, '(ohne Panel)') AS panel, COUNT(*) AS anzahl, "
						+ "sum(size_bytes) AS bytes FROM legacy_transcripts WHERE guild_id = ? "
						+ "GROUP BY panel ORDER BY anzahl DESC LIMIT 15",
				rs -> {
					sb.append(String.format("%5d · %s (%.1f MB)%n",
							rs.getInt("anzahl"), rs.getString("panel"),
							rs.getLong("bytes") / 1024.0 / 1024.0));
					return null;
				}, guildId);

		event.replyEmbeds(MessageUtil.embed("Altdaten", sb.toString(), 0x1ec45c))
				.setEphemeral(true).queue();
	}
}
