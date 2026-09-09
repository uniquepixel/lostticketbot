package model;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;

public record Ticket(
		long id,
		String guildId,
		Long panelId,
		int number,
		String channelId,
		String channelName,
		String ownerId,
		String claimedBy,
		Status status,
		LocalDateTime openedAt,
		LocalDateTime closedAt,
		String closedBy,
		String closeReason) {

	public enum Status {
		OPEN, CLOSED, DELETED;

		public static Status of(String raw) {
			return switch (raw == null ? "" : raw.toLowerCase()) {
				case "closed" -> CLOSED;
				case "deleted" -> DELETED;
				default -> OPEN;
			};
		}

		public String dbValue() {
			return name().toLowerCase();
		}
	}

	public static Ticket from(ResultSet rs) throws SQLException {
		final long panelRaw = rs.getLong("panel_id");
		// Siehe Menu.from: wasNull() bezieht sich auf die zuletzt gelesene Spalte.
		final Long panel = rs.wasNull() ? null : panelRaw;
		return new Ticket(
				rs.getLong("id"),
				rs.getString("guild_id"),
				panel,
				rs.getInt("number"),
				rs.getString("channel_id"),
				rs.getString("channel_name"),
				rs.getString("owner_id"),
				rs.getString("claimed_by"),
				Status.of(rs.getString("status")),
				toLocal(rs, "opened_at"),
				toLocal(rs, "closed_at"),
				rs.getString("closed_by"),
				rs.getString("close_reason"));
	}

	private static LocalDateTime toLocal(ResultSet rs, String column) throws SQLException {
		final var ts = rs.getTimestamp(column);
		return ts == null ? null : ts.toLocalDateTime();
	}

	public boolean isOpen() {
		return status == Status.OPEN;
	}
}
