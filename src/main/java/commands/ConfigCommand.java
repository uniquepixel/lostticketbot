package commands;

import javax.annotation.Nonnull;

import db.Database;
import db.GuildConfigDao;
import model.GuildConfig;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import util.MessageUtil;

/**
 * Serverweite Einstellungen und die Blacklist.
 *
 * Das globale Ticketlimit gilt zusaetzlich zum Limit einzelner Panels; die
 * kleinere Zahl gewinnt. Im Bestand stehen die beiden Server hier bewusst weit
 * auseinander - Bewerbungen erlaubt genau ein offenes Ticket pro Person,
 * LOST Family faktisch beliebig viele.
 */
public class ConfigCommand extends ListenerAdapter {

	public static final String NAME = "ticketconfig";

	@Override
	public void onSlashCommandInteraction(@Nonnull SlashCommandInteractionEvent event) {
		if (!NAME.equals(event.getName()) || event.getGuild() == null) {
			return;
		}
		final String guildId = event.getGuild().getId();

		switch (String.valueOf(event.getSubcommandName())) {
			case "zeigen" -> show(event, guildId);
			case "limit" -> limit(event, guildId);
			case "sperren" -> block(event, guildId, false);
			case "entsperren" -> block(event, guildId, true);
			case "sperrliste" -> blocklist(event, guildId);
			default -> event.reply("Unbekannter Unterbefehl.").setEphemeral(true).queue();
		}
	}

	private void show(SlashCommandInteractionEvent event, String guildId) {
		final GuildConfig c = GuildConfigDao.get(guildId);
		final int gesperrt = Database.count("SELECT COUNT(*) FROM blacklist WHERE guild_id = ?", guildId);
		final int panels = Database.count("SELECT COUNT(*) FROM panels WHERE guild_id = ?", guildId);
		final int offen = Database.count(
				"SELECT COUNT(*) FROM tickets WHERE guild_id = ? AND status = 'open'", guildId);

		event.replyEmbeds(MessageUtil.embed("Konfiguration", """
				**Globales Ticketlimit:** %s
				**Panels:** %d
				**Offene Tickets:** %d
				**Gesperrt:** %d Einträge"""
				.formatted(c.hasGlobalLimit() ? String.valueOf(c.globalMaxOpenTickets()) : "unbegrenzt",
						panels, offen, gesperrt),
				c.ticketEmbedColor())).setEphemeral(true).queue();
	}

	private void limit(SlashCommandInteractionEvent event, String guildId) {
		final int wert = event.getOption("anzahl", 0L, OptionMapping::getAsLong).intValue();
		if (wert < 0) {
			event.replyEmbeds(MessageUtil.error("Die Zahl darf nicht negativ sein. 0 heisst unbegrenzt."))
					.setEphemeral(true).queue();
			return;
		}
		GuildConfigDao.setGlobalLimit(guildId, wert);
		event.replyEmbeds(MessageUtil.embed("Gespeichert",
				wert == 0
						? "Serverweit gibt es jetzt keine Obergrenze mehr; nur noch die Panel-Limits gelten."
						: "Jede Person darf serverweit hoechstens **" + wert + "** offene Tickets haben.",
				0x1ec45c)).setEphemeral(true).queue();
	}

	private void block(SlashCommandInteractionEvent event, String guildId, boolean entsperren) {
		final var mentionable = event.getOption("wen").getAsMentionable();
		final String id = mentionable.getId();
		final boolean istRolle = event.getOption("wen").getAsMentionable() instanceof net.dv8tion.jda.api.entities.Role;

		if (entsperren) {
			Database.update("DELETE FROM blacklist WHERE guild_id = ? AND subject_id = ?", guildId, id);
			event.replyEmbeds(MessageUtil.embed("Entsperrt",
					mentionable.getAsMention() + " darf wieder Tickets oeffnen.", 0x1ec45c))
					.setEphemeral(true).queue();
			return;
		}

		final String grund = event.getOption("grund", null, OptionMapping::getAsString);
		Database.update(
				"INSERT INTO blacklist (guild_id, subject_id, is_role, reason, added_by) "
						+ "VALUES (?, ?, ?, ?, ?) ON CONFLICT (guild_id, subject_id) "
						+ "DO UPDATE SET reason = EXCLUDED.reason, added_by = EXCLUDED.added_by",
				guildId, id, istRolle, grund, event.getUser().getId());
		event.replyEmbeds(MessageUtil.embed("Gesperrt",
				mentionable.getAsMention() + " kann keine Tickets mehr oeffnen."
						+ (grund == null ? "" : "\nGrund: " + grund),
				0x1ec45c)).setEphemeral(true).queue();
	}

	private void blocklist(SlashCommandInteractionEvent event, String guildId) {
		final StringBuilder sb = new StringBuilder();
		Database.query(
				"SELECT subject_id, is_role, reason FROM blacklist WHERE guild_id = ? ORDER BY added_at DESC",
				rs -> {
					sb.append(rs.getBoolean("is_role") ? "<@&" : "<@")
							.append(rs.getString("subject_id")).append('>');
					final String grund = rs.getString("reason");
					if (grund != null) {
						sb.append(" — ").append(grund);
					}
					sb.append('\n');
					return null;
				}, guildId);

		event.replyEmbeds(MessageUtil.embed("Sperrliste",
				sb.length() == 0 ? "Niemand gesperrt." : sb.toString(), 0x1ec45c))
				.setEphemeral(true).queue();
	}
}
