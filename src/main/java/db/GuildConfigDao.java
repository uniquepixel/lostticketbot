package db;

import model.GuildConfig;

/** Serverweite Einstellungen. Fehlt ein Server, wird er beim ersten Zugriff angelegt. */
public final class GuildConfigDao {

	private GuildConfigDao() {
	}

	public static GuildConfig get(String guildId) {
		return Database
				.queryOne("SELECT * FROM guild_config WHERE guild_id = ?", GuildConfig::from, guildId)
				.orElseGet(() -> {
					ensure(guildId);
					return GuildConfig.defaults(guildId);
				});
	}

	public static void ensure(String guildId) {
		Database.update(
				"INSERT INTO guild_config (guild_id, panel_embed_color, ticket_embed_color) "
						+ "VALUES (?, ?, ?) ON CONFLICT (guild_id) DO NOTHING",
				guildId, GuildConfig.DEFAULT_PANEL_COLOR, GuildConfig.DEFAULT_TICKET_COLOR);
	}

	public static void setGlobalLimit(String guildId, int limit) {
		ensure(guildId);
		Database.update("UPDATE guild_config SET global_max_open_tickets = ? WHERE guild_id = ?", limit, guildId);
	}
}
