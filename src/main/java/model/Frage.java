package model;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Eine Frage, die beim Öffnen eines Tickets als Formularfeld erscheint.
 *
 * Discord erlaubt höchstens fünf Felder je Formular; die Grenzen der einzelnen
 * Werte prüft {@link verwaltung.Verwaltung#fragenSetzen}.
 */
public record Frage(
		long id,
		long panelId,
		int position,
		String label,
		String placeholder,
		boolean langeAntwort,
		boolean pflicht,
		Integer maxLaenge) {

	public static Frage from(ResultSet rs) throws SQLException {
		final int max = rs.getInt("max_length");
		final Integer maxLaenge = rs.wasNull() ? null : max;
		return new Frage(
				rs.getLong("id"),
				rs.getLong("panel_id"),
				rs.getInt("position"),
				rs.getString("label"),
				rs.getString("placeholder"),
				rs.getBoolean("long_answer"),
				rs.getBoolean("required"),
				maxLaenge);
	}
}
