package db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

import model.Frage;

/** Die Formularfragen eines Panels. */
public final class FrageDao {

	private FrageDao() {
	}

	public static List<Frage> vonPanel(long panelId) {
		return Database.query(
				"SELECT * FROM panel_questions WHERE panel_id = ? ORDER BY position, id",
				Frage::from, panelId);
	}

	/**
	 * Ersetzt alle Fragen eines Panels in einer Transaktion.
	 *
	 * Ganz oder gar nicht: ein halb geschriebenes Formular, bei dem die alten
	 * Fragen schon weg und die neuen noch nicht da sind, würde ein Ticket ohne
	 * die Angaben öffnen, auf die das Team wartet.
	 */
	public static void ersetzen(long panelId, List<Frage> fragen) {
		try (Connection conn = Database.getConnection()) {
			conn.setAutoCommit(false);
			try {
				try (PreparedStatement del = conn.prepareStatement(
						"DELETE FROM panel_questions WHERE panel_id = ?")) {
					del.setLong(1, panelId);
					del.executeUpdate();
				}
				try (PreparedStatement ins = conn.prepareStatement(
						"INSERT INTO panel_questions (panel_id, position, label, placeholder, long_answer, "
								+ "required, max_length) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
					int pos = 0;
					for (final Frage f : fragen) {
						ins.setLong(1, panelId);
						ins.setInt(2, pos++);
						ins.setString(3, f.label());
						ins.setString(4, f.placeholder());
						ins.setBoolean(5, f.langeAntwort());
						ins.setBoolean(6, f.pflicht());
						if (f.maxLaenge() == null) {
							ins.setNull(7, java.sql.Types.SMALLINT);
						} else {
							ins.setInt(7, f.maxLaenge());
						}
						ins.addBatch();
					}
					ins.executeBatch();
				}
				conn.commit();
			} catch (final SQLException e) {
				conn.rollback();
				throw e;
			} finally {
				conn.setAutoCommit(true);
			}
		} catch (final SQLException e) {
			throw new Database.DatabaseException("Fragen konnten nicht gespeichert werden", e);
		}
	}
}
