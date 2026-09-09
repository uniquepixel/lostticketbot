package db;

import java.util.List;
import java.util.Optional;

import model.Ticket;

public final class TicketDao {

	private static final String SELECT = "SELECT * FROM tickets ";

	private TicketDao() {
	}

	public static Optional<Ticket> byId(long id) {
		return Database.queryOne(SELECT + "WHERE id = ?", Ticket::from, id);
	}

	public static Optional<Ticket> byChannel(String channelId) {
		return Database.queryOne(SELECT + "WHERE channel_id = ?", Ticket::from, channelId);
	}

	public static long create(String guildId, long panelId, int number, String channelId,
			String channelName, String ownerId) {
		return Database.insert(
				"INSERT INTO tickets (guild_id, panel_id, number, channel_id, channel_name, owner_id) "
						+ "VALUES (?, ?, ?, ?, ?, ?)",
				guildId, panelId, number, channelId, channelName, ownerId);
	}

	/**
	 * Offene Tickets eines Nutzers serverweit. Grundlage fuer das globale Limit,
	 * das auf dem Bewerbungsserver auf 1 steht und dort die eigentlich wirksame
	 * Grenze ist - nicht das Panel-Limit.
	 */
	public static int countOpenByOwner(String guildId, String ownerId) {
		return Database.count(
				"SELECT COUNT(*) FROM tickets WHERE guild_id = ? AND owner_id = ? AND status = 'open'",
				guildId, ownerId);
	}

	public static int countOpenByOwnerAndPanel(long panelId, String ownerId) {
		return Database.count(
				"SELECT COUNT(*) FROM tickets WHERE panel_id = ? AND owner_id = ? AND status = 'open'",
				panelId, ownerId);
	}

	public static List<Ticket> openByOwner(String guildId, String ownerId) {
		return Database.query(SELECT + "WHERE guild_id = ? AND owner_id = ? AND status = 'open'",
				Ticket::from, guildId, ownerId);
	}

	public static List<Ticket> allOpen(String guildId) {
		return Database.query(SELECT + "WHERE guild_id = ? AND status = 'open' ORDER BY opened_at",
				Ticket::from, guildId);
	}

	/** Zeitpunkt des letzten Tickets dieses Nutzers an diesem Panel - fuer den Cooldown. */
	public static Optional<java.time.LocalDateTime> lastOpenedAt(long panelId, String ownerId) {
		return Database.queryOne(
				"SELECT MAX(opened_at) FROM tickets WHERE panel_id = ? AND owner_id = ?",
				rs -> {
					final var ts = rs.getTimestamp(1);
					return ts == null ? null : ts.toLocalDateTime();
				}, panelId, ownerId);
	}

	public static void close(long ticketId, String closedBy, String reason, String newChannelName) {
		Database.update(
				"UPDATE tickets SET status = 'closed', closed_at = CURRENT_TIMESTAMP, closed_by = ?, "
						+ "close_reason = ?, channel_name = ? WHERE id = ?",
				closedBy, reason, newChannelName, ticketId);
	}

	public static void reopen(long ticketId, String newChannelName) {
		Database.update(
				"UPDATE tickets SET status = 'open', closed_at = NULL, closed_by = NULL, "
						+ "close_reason = NULL, channel_name = ? WHERE id = ?",
				newChannelName, ticketId);
	}

	public static void markDeleted(long ticketId) {
		Database.update("UPDATE tickets SET status = 'deleted' WHERE id = ?", ticketId);
	}

	public static void setClaim(long ticketId, String userId) {
		Database.update("UPDATE tickets SET claimed_by = ? WHERE id = ?", userId, ticketId);
	}

	/**
	 * Traegt den echten Oeffnungszeitpunkt nach.
	 *
	 * Gebraucht bei der Uebernahme aus Ticket Tool: dort ist der Zeitpunkt des
	 * Uebernehmens nicht der Zeitpunkt des Oeffnens - manche der uebernommenen
	 * Tickets sind Jahre alt. Ohne das zeigt die Statistik alle am selben Tag
	 * eroeffnet.
	 */
	public static void setOpenedAt(long ticketId, java.time.OffsetDateTime zeitpunkt) {
		Database.update("UPDATE tickets SET opened_at = ? WHERE id = ?",
				java.sql.Timestamp.from(zeitpunkt.toInstant()), ticketId);
	}

	public static void setChannelName(long ticketId, String name) {
		Database.update("UPDATE tickets SET channel_name = ? WHERE id = ?", name, ticketId);
	}

	// -- manuell hinzugefuegte Nutzer -------------------------------------------

	public static void addMember(long ticketId, String userId, String addedBy) {
		Database.update(
				"INSERT INTO ticket_members (ticket_id, user_id, added_by) VALUES (?, ?, ?) "
						+ "ON CONFLICT DO NOTHING",
				ticketId, userId, addedBy);
	}

	public static void removeMember(long ticketId, String userId) {
		Database.update("DELETE FROM ticket_members WHERE ticket_id = ? AND user_id = ?", ticketId, userId);
	}

	public static List<String> members(long ticketId) {
		return Database.query("SELECT user_id FROM ticket_members WHERE ticket_id = ?",
				rs -> rs.getString(1), ticketId);
	}
}
