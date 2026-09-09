package model;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Ein Menü ist die Nachricht, über die Tickets geöffnet werden — als Reihe von
 * Knöpfen oder als Dropdown. Es enthält selbst keine Ticket-Konfiguration,
 * sondern verweist über {@link MenuEntry} auf {@link Panel}s.
 *
 * Diese Trennung ist der Grund, warum ein totes Panel (Rh13, Lost 8) einfach
 * aus dem Menü genommen werden kann, ohne seine Alt-Tickets zu verwaisen.
 */
public record Menu(
		long id,
		String guildId,
		String channelId,
		String messageId,
		Style style,
		String embedTitle,
		String embedText,
		Integer embedColor,
		String placeholder) {

	public enum Style {
		BUTTONS, SELECT;

		public static Style of(String raw) {
			return "select".equalsIgnoreCase(raw) ? SELECT : BUTTONS;
		}

		public String dbValue() {
			return name().toLowerCase();
		}
	}

	public static Menu from(ResultSet rs) throws SQLException {
		final int colorRaw = rs.getInt("embed_color");
		// wasNull() gilt immer fuer die zuletzt gelesene Spalte - deshalb sofort
		// nach getInt auswerten und nicht erst im Konstruktoraufruf.
		final Integer color = rs.wasNull() ? null : colorRaw;
		return new Menu(
				rs.getLong("id"),
				rs.getString("guild_id"),
				rs.getString("channel_id"),
				rs.getString("message_id"),
				Style.of(rs.getString("style")),
				rs.getString("embed_title"),
				rs.getString("embed_text"),
				color,
				rs.getString("placeholder"));
	}

	/** Ein Menü ohne message_id wurde noch nie gepostet. */
	public boolean isPosted() {
		return messageId != null && !messageId.isBlank();
	}
}
