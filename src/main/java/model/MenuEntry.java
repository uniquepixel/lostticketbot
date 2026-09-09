package model;

import java.sql.ResultSet;
import java.sql.SQLException;

/** Ein Eintrag im Menü: welches Panel unter welcher Beschriftung angeboten wird. */
public record MenuEntry(
		long menuId,
		long panelId,
		String label,
		String description,
		String emoji,
		int buttonStyle,
		int position) {

	public static MenuEntry from(ResultSet rs) throws SQLException {
		return new MenuEntry(
				rs.getLong("menu_id"),
				rs.getLong("panel_id"),
				rs.getString("label"),
				rs.getString("description"),
				rs.getString("emoji"),
				rs.getInt("button_style"),
				rs.getInt("position"));
	}
}
