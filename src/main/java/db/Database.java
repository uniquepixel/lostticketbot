package db;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Zugang zur Datenbank.
 *
 * Anders als die übrigen LOST-Bots wird hier nicht eine einzige statische
 * Verbindung geteilt, sondern ein kleiner Pool benutzt — der Transcript-
 * Recorder schreibt bei jeder Nachricht mit und würde eine geteilte Verbindung
 * dauerhaft belegen.
 *
 * Fehler werden bewusst NICHT verschluckt. Ein stiller Schreibfehler im
 * Transcript fällt sonst erst auf, wenn jemand ein Ticket nachlesen will und es
 * halb leer ist.
 */
public final class Database {

	private static HikariDataSource dataSource;

	private Database() {
	}

	public static void init(String url, String user, String password) {
		final HikariConfig config = new HikariConfig();
		config.setJdbcUrl(url);
		config.setUsername(user);
		config.setPassword(password);
		config.setPoolName("ticketbot");
		// Klein halten: der Bot ist kein Webserver, und Postgres teilt sich die
		// Maschine mit mehreren anderen Diensten.
		config.setMaximumPoolSize(6);
		config.setMinimumIdle(1);
		config.setConnectionTimeout(10_000);
		config.setValidationTimeout(5_000);
		config.setKeepaliveTime(60_000);
		// Bricht hängende Socket-Reads ab, statt einen Thread ewig zu blockieren.
		config.addDataSourceProperty("socketTimeout", "30");
		config.addDataSourceProperty("connectTimeout", "10");
		config.addDataSourceProperty("tcpKeepAlive", "true");
		dataSource = new HikariDataSource(config);
	}

	public static void shutdown() {
		if (dataSource != null && !dataSource.isClosed()) {
			dataSource.close();
		}
	}

	public static Connection getConnection() throws SQLException {
		if (dataSource == null) {
			throw new IllegalStateException("Database.init() wurde nicht aufgerufen.");
		}
		return dataSource.getConnection();
	}

	/**
	 * Legt fehlende Tabellen an. schema.sql ist durchgehend mit
	 * {@code CREATE TABLE IF NOT EXISTS} geschrieben und darf deshalb bei jedem
	 * Start laufen.
	 */
	public static void applySchema() {
		final String sql = readResource("/schema.sql");
		try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
			stmt.execute(sql);
			System.out.println("Schema geprüft und aktuell.");
		} catch (final SQLException e) {
			throw new DatabaseException("Schema konnte nicht angewendet werden", e);
		}
	}

	private static String readResource(String path) {
		try (InputStream in = Database.class.getResourceAsStream(path)) {
			if (in == null) {
				throw new DatabaseException("Ressource fehlt im Jar: " + path, null);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (final IOException e) {
			throw new DatabaseException("Ressource nicht lesbar: " + path, e);
		}
	}

	// -----------------------------------------------------------------------
	// Abfragen
	// -----------------------------------------------------------------------

	@FunctionalInterface
	public interface RowMapper<T> {
		T map(ResultSet rs) throws SQLException;
	}

	public static int update(String sql, Object... params) {
		try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
			bind(ps, params);
			return ps.executeUpdate();
		} catch (final SQLException e) {
			throw new DatabaseException("Update fehlgeschlagen: " + sql, e);
		}
	}

	/** Führt ein INSERT aus und gibt die erzeugte BIGSERIAL-id zurück. */
	public static long insert(String sql, Object... params) {
		try (Connection conn = getConnection();
				PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
			bind(ps, params);
			ps.executeUpdate();
			try (ResultSet keys = ps.getGeneratedKeys()) {
				if (keys.next()) {
					return keys.getLong(1);
				}
			}
			throw new DatabaseException("INSERT lieferte keine id: " + sql, null);
		} catch (final SQLException e) {
			throw new DatabaseException("Insert fehlgeschlagen: " + sql, e);
		}
	}

	public static <T> List<T> query(String sql, RowMapper<T> mapper, Object... params) {
		final List<T> out = new ArrayList<>();
		try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
			bind(ps, params);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					out.add(mapper.map(rs));
				}
			}
		} catch (final SQLException e) {
			throw new DatabaseException("Abfrage fehlgeschlagen: " + sql, e);
		}
		return out;
	}

	public static <T> Optional<T> queryOne(String sql, RowMapper<T> mapper, Object... params) {
		final List<T> rows = query(sql, mapper, params);
		return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
	}

	public static int count(String sql, Object... params) {
		return queryOne(sql, rs -> rs.getInt(1), params).orElse(0);
	}

	private static void bind(PreparedStatement ps, Object... params) throws SQLException {
		for (int i = 0; i < params.length; i++) {
			ps.setObject(i + 1, params[i]);
		}
	}

	/** Signalisiert einen Datenbankfehler, statt ihn zu verschlucken. */
	public static class DatabaseException extends RuntimeException {
		private static final long serialVersionUID = 1L;

		public DatabaseException(String message, Throwable cause) {
			super(message, cause);
		}
	}
}
