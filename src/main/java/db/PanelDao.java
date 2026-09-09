package db;

import java.util.List;
import java.util.Optional;

import model.Panel;

/** Lesen und Schreiben von Panels und ihren Rollen. */
public final class PanelDao {

	private static final String SELECT = "SELECT * FROM panels ";

	private PanelDao() {
	}

	public static Optional<Panel> byId(long id) {
		return Database.queryOne(SELECT + "WHERE id = ?", Panel::from, id);
	}

	public static Optional<Panel> byName(String guildId, String name) {
		return Database.queryOne(SELECT + "WHERE guild_id = ? AND name = ?", Panel::from, guildId, name);
	}

	public static List<Panel> byGuild(String guildId) {
		return Database.query(SELECT + "WHERE guild_id = ? ORDER BY name", Panel::from, guildId);
	}

	/**
	 * Erhöht den Zähler und gibt den neuen Stand zurück — in einer Anweisung,
	 * damit zwei gleichzeitig geöffnete Tickets nicht dieselbe Nummer bekommen.
	 * Lesen-dann-Schreiben wäre hier ein echtes Rennen: beim Bewerbungsserver
	 * kamen zu Spitzenzeiten knapp 400 Tickets im Monat rein.
	 */
	public static int nextNumber(long panelId) {
		return Database
				.queryOne("UPDATE panels SET counter = counter + 1 WHERE id = ? RETURNING counter",
						rs -> rs.getInt(1), panelId)
				.orElseThrow(() -> new Database.DatabaseException("Panel " + panelId + " existiert nicht", null));
	}

	public static List<String> roles(long panelId, String kind) {
		return Database.query(
				"SELECT role_id FROM panel_roles WHERE panel_id = ? AND kind = ?",
				rs -> rs.getString(1), panelId, kind);
	}

	/** Rollen mit Zugriff auf das Ticket. */
	public static List<String> supportRoles(long panelId) {
		return roles(panelId, "support");
	}

	/** Rollen, die beim Öffnen gepingt werden. */
	public static List<String> pingRoles(long panelId) {
		return roles(panelId, "ping");
	}

	public static void addRole(long panelId, String roleId, String kind) {
		Database.update(
				"INSERT INTO panel_roles (panel_id, role_id, kind) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
				panelId, roleId, kind);
	}

	public static void removeRole(long panelId, String roleId, String kind) {
		Database.update("DELETE FROM panel_roles WHERE panel_id = ? AND role_id = ? AND kind = ?",
				panelId, roleId, kind);
	}

	public static long create(String guildId, String name) {
		return Database.insert("INSERT INTO panels (guild_id, name) VALUES (?, ?)", guildId, name);
	}

	/** Setzt eine einzelne Spalte. Der Spaltenname kommt aus dem Code, nie aus Nutzereingaben. */
	public static void set(long panelId, String column, Object value) {
		if (!ALLOWED_COLUMNS.contains(column)) {
			throw new IllegalArgumentException("Nicht erlaubte Spalte: " + column);
		}
		Database.update("UPDATE panels SET " + column + " = ? WHERE id = ?", value, panelId);
	}

	private static final List<String> ALLOWED_COLUMNS = List.of(
			"name", "active", "category_opened", "category_closed",
			"name_pattern_open", "name_pattern_closed", "counter", "counter_padding",
			"welcome_text", "welcome_embed_title", "welcome_embed_text", "log_channel_id",
			"max_open_per_user", "cooldown_seconds", "close_on_owner_leave",
			"owner_leave_message", "dm_on_open", "dm_on_close");
}
