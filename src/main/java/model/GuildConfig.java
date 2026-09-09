package model;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Serverweite Einstellungen.
 *
 * {@code globalMaxOpenTickets} bildet Ticket Tools serverweites Limit ab, das
 * zusätzlich zum Panel-Limit greift. Im Bestand stehen die beiden Server hier
 * bewusst weit auseinander: Bewerbungen = 1 (ein Bewerber darf serverweit genau
 * ein offenes Ticket haben), Family = 100 (faktisch unbegrenzt).
 */
public record GuildConfig(
		String guildId,
		int globalMaxOpenTickets,
		int panelEmbedColor,
		int ticketEmbedColor) {

	public static final int DEFAULT_PANEL_COLOR = 0x388e3c;
	public static final int DEFAULT_TICKET_COLOR = 0x1ec45c;

	public static GuildConfig from(ResultSet rs) throws SQLException {
		return new GuildConfig(
				rs.getString("guild_id"),
				rs.getInt("global_max_open_tickets"),
				rs.getInt("panel_embed_color"),
				rs.getInt("ticket_embed_color"));
	}

	public static GuildConfig defaults(String guildId) {
		return new GuildConfig(guildId, 0, DEFAULT_PANEL_COLOR, DEFAULT_TICKET_COLOR);
	}

	public boolean hasGlobalLimit() {
		return globalMaxOpenTickets > 0;
	}
}
