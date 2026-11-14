package db;

import java.util.List;
import java.util.Optional;

import model.Menu;
import model.MenuEntry;

public final class MenuDao {

	private MenuDao() {
	}

	public static Optional<Menu> byId(long id) {
		return Database.queryOne("SELECT * FROM menus WHERE id = ?", Menu::from, id);
	}

	public static Optional<Menu> byMessage(String messageId) {
		return Database.queryOne("SELECT * FROM menus WHERE message_id = ?", Menu::from, messageId);
	}

	public static List<Menu> byGuild(String guildId) {
		return Database.query("SELECT * FROM menus WHERE guild_id = ? ORDER BY id", Menu::from, guildId);
	}

	public static List<MenuEntry> entries(long menuId) {
		return Database.query(
				"SELECT * FROM menu_entries WHERE menu_id = ? ORDER BY position, label",
				MenuEntry::from, menuId);
	}

	public static long create(String guildId, String channelId, Menu.Style style) {
		return Database.insert(
				"INSERT INTO menus (guild_id, channel_id, style) VALUES (?, ?, ?)",
				guildId, channelId, style.dbValue());
	}

	public static void setMessageId(long menuId, String messageId) {
		Database.update("UPDATE menus SET message_id = ? WHERE id = ?", messageId, menuId);
	}

	public static void setEmbed(long menuId, String title, String text, Integer color, String placeholder) {
		Database.update(
				"UPDATE menus SET embed_title = ?, embed_text = ?, embed_color = ?, placeholder = ? WHERE id = ?",
				title, text, color, placeholder, menuId);
	}

	public static void addEntry(long menuId, long panelId, String label, String description,
			String emoji, int buttonStyle, int position) {
		Database.update(
				"INSERT INTO menu_entries (menu_id, panel_id, label, description, emoji, button_style, position) "
						+ "VALUES (?, ?, ?, ?, ?, ?, ?) "
						+ "ON CONFLICT (menu_id, panel_id) DO UPDATE SET label = EXCLUDED.label, "
						+ "description = EXCLUDED.description, emoji = EXCLUDED.emoji, "
						+ "button_style = EXCLUDED.button_style, position = EXCLUDED.position",
				menuId, panelId, label, description, emoji, buttonStyle, position);
	}

	public static void setStyle(long menuId, Menu.Style style) {
		Database.update("UPDATE menus SET style = ? WHERE id = ?", style.dbValue(), menuId);
	}

	/** Zieht ein Menü in einen anderen Kanal um; es gilt danach als nie gepostet. */
	public static void setChannel(long menuId, String channelId) {
		Database.update("UPDATE menus SET channel_id = ?, message_id = NULL WHERE id = ?", channelId, menuId);
	}

	/** Löscht ein Menü samt Einträgen. Panels und Tickets bleiben unberührt. */
	public static void delete(long menuId) {
		Database.update("DELETE FROM menus WHERE id = ?", menuId);
	}

	public static List<Menu> withPanel(long panelId) {
		return Database.query(
				"SELECT m.* FROM menus m JOIN menu_entries e ON e.menu_id = m.id WHERE e.panel_id = ? ORDER BY m.id",
				Menu::from, panelId);
	}

	/** Nimmt ein Panel aus dem Menue, ohne es oder seine Tickets anzutasten. */
	public static void removeEntry(long menuId, long panelId) {
		Database.update("DELETE FROM menu_entries WHERE menu_id = ? AND panel_id = ?", menuId, panelId);
	}
}
