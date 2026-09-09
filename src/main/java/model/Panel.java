package model;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;

/**
 * Ein Panel ist ein Ticket-Typ mit eigenem Zähler und vollständiger
 * Konfiguration — nicht eine Nachricht mit Knöpfen. Die Nachricht ist das
 * {@link Menu}, das ein oder mehrere Panels anbietet.
 *
 * Dieses Modell stammt aus dem Bestand: das Rathaus-Dropdown des
 * Bewerbungsservers besteht aus sechs Panels mit sechs getrennten Zählern
 * (th17 stand bei 454, th18 bei 152), nicht aus einem Panel mit sechs Optionen.
 */
public record Panel(
		long id,
		String guildId,
		String name,
		boolean active,
		String categoryOpened,
		String categoryClosed,
		String namePatternOpen,
		String namePatternClosed,
		int counter,
		int counterPadding,
		String welcomeText,
		String welcomeEmbedTitle,
		String welcomeEmbedText,
		String logChannelId,
		int maxOpenPerUser,
		int cooldownSeconds,
		boolean closeOnOwnerLeave,
		String ownerLeaveMessage,
		boolean dmOnOpen,
		boolean dmOnClose) {

	public static Panel from(ResultSet rs) throws SQLException {
		return new Panel(
				rs.getLong("id"),
				rs.getString("guild_id"),
				rs.getString("name"),
				rs.getBoolean("active"),
				rs.getString("category_opened"),
				rs.getString("category_closed"),
				rs.getString("name_pattern_open"),
				rs.getString("name_pattern_closed"),
				rs.getInt("counter"),
				rs.getInt("counter_padding"),
				rs.getString("welcome_text"),
				rs.getString("welcome_embed_title"),
				rs.getString("welcome_embed_text"),
				rs.getString("log_channel_id"),
				rs.getInt("max_open_per_user"),
				rs.getInt("cooldown_seconds"),
				rs.getBoolean("close_on_owner_leave"),
				rs.getString("owner_leave_message"),
				rs.getBoolean("dm_on_open"),
				rs.getBoolean("dm_on_close"));
	}

	/**
	 * Baut den Kanalnamen aus dem Muster.
	 *
	 * Offen und geschlossen haben bewusst getrennte Muster, weil der Bestand das
	 * erzwingt: das Orga-Panel macht aus {@code orga-ticket-0814} schlicht
	 * {@code closed-0815} und wirft den Präfix weg, während alle anderen
	 * {@code -closed-} in der Mitte einschieben.
	 *
	 * Das Ergebnis wird auf Discords Kanalnamensregeln normalisiert. Genau daran
	 * scheitert Ticket Tool im Bestand sichtbar: dort stehen Muster wie
	 * {@code "TH 15-{count}"} mit Leerzeichen, aus denen Discord {@code th-15-…}
	 * macht — ein Tippfehler, den niemand bemerkt hat, weil das Ergebnis
	 * plausibel aussah.
	 */
	public String renderName(boolean closed, int number, String username) {
		final String pattern = closed ? namePatternClosed : namePatternOpen;
		final String counterText = counterPadding > 1
				? String.format("%0" + counterPadding + "d", number)
				: Integer.toString(number);
		final String raw = pattern
				.replace("{count}", counterText)
				.replace("{user}", username == null ? "" : username);
		return sanitizeChannelName(raw);
	}

	/**
	 * Bringt einen Namen in die Form, die Discord ohnehin erzwingen würde —
	 * damit steht in der Datenbank derselbe Name wie im Kanal und nicht die
	 * hübschere Wunschform.
	 */
	public static String sanitizeChannelName(String raw) {
		String s = raw.toLowerCase(Locale.ROOT).trim();
		s = s.replace('_', '-').replaceAll("\\s+", "-");
		s = s.replaceAll("[^a-z0-9\\-äöüß]", "");
		s = s.replaceAll("-{2,}", "-").replaceAll("^-|-$", "");
		if (s.isEmpty()) {
			s = "ticket";
		}
		return s.length() > 100 ? s.substring(0, 100) : s;
	}
}
