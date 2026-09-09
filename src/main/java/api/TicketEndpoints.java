package api;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import api.TicketApiServer.Anfrage;
import db.Database;
import db.PanelDao;
import db.TicketDao;
import lostticketbot.Bot;
import model.Panel;
import model.Ticket;
import ticket.Visibility;
import transcript.AnhangUrl;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;

/**
 * Die Endpunkte des Dashboards.
 *
 * Jede Abfrage laeuft ueber {@link #sichtbarePanels}. Es gibt bewusst keinen
 * Weg, Tickets zu lesen, ohne dass vorher geprueft wurde, wer fragt.
 */
public final class TicketEndpoints {

	private TicketEndpoints() {
	}

	// -----------------------------------------------------------------------
	// Sichtbarkeit
	// -----------------------------------------------------------------------

	/**
	 * Welche Panels darf dieser Nutzer im Dashboard sehen?
	 *
	 * Grundregel: dieselben wie in Discord. Wer die Support-Rolle eines Panels
	 * traegt, sieht dessen Tickets; wer den Server verwalten darf, sieht alles.
	 *
	 * Das ist nicht nur bequem, sondern noetig: die Orga-Tickets auf LOST Family
	 * enthalten Beschwerden ueber namentlich genannte Anfuehrer. Haenge man die
	 * Sichtbarkeit an der Rollenhierarchie der Website auf, koennte jeder
	 * Anfuehrer nachlesen, wer sich ueber ihn beschwert hat.
	 */
	static List<Panel> sichtbarePanels(String guildId, String discordUserId) {
		return Visibility.sichtbarePanels(guild(guildId), discordUserId);
	}

	private static Guild guild(String guildId) {
		final Guild guild = Bot.jda() == null ? null : Bot.jda().getGuildById(guildId);
		if (guild == null) {
			throw new IllegalArgumentException("Unbekannter Server: " + guildId);
		}
		return guild;
	}

	private static String guildIdVon(Anfrage anfrage) {
		final String guildId = anfrage.param("guild");
		if (guildId == null || guildId.isBlank()) {
			throw new IllegalArgumentException("Parameter 'guild' fehlt");
		}
		return guildId;
	}

	// -----------------------------------------------------------------------
	// /api/guilds
	// -----------------------------------------------------------------------

	/**
	 * Auf welchen Servern hat dieser Nutzer ueberhaupt etwas zu sehen?
	 *
	 * Damit muss die Website keine Server-IDs kennen. Sie fragt hier, bekommt
	 * genau die Server zurueck, fuer die der Anfragende Rechte hat, und haengt
	 * ihre weiteren Abfragen an die gelieferten IDs. Wer nirgends Support-Rolle
	 * hat, bekommt eine leere Liste — und die Website zeigt gar kein Dashboard,
	 * statt eines mit lauter Nullen.
	 */
	public static Object guilds(Anfrage anfrage) {
		final List<Map<String, Object>> aus = new ArrayList<>();
		if (Bot.jda() == null) {
			return TicketApiServer.liste(aus, 0);
		}
		for (final Guild guild : Bot.jda().getGuilds()) {
			final List<Panel> sichtbar = Visibility.sichtbarePanels(guild, anfrage.discordUserId());
			if (sichtbar.isEmpty()) {
				continue;
			}
			final Map<String, Object> m = new LinkedHashMap<>();
			m.put("id", guild.getId());
			m.put("name", guild.getName());
			m.put("icon", guild.getIconUrl());
			m.put("panels", sichtbar.size());
			aus.add(m);
		}
		return TicketApiServer.liste(aus, aus.size());
	}

	// -----------------------------------------------------------------------
	// /api/panels
	// -----------------------------------------------------------------------

	public static Object panels(Anfrage anfrage) {
		final String guildId = guildIdVon(anfrage);
		final List<Map<String, Object>> aus = new ArrayList<>();

		for (final Panel p : sichtbarePanels(guildId, anfrage.discordUserId())) {
			final Map<String, Object> m = new LinkedHashMap<>();
			m.put("id", p.id());
			m.put("name", p.name());
			m.put("active", p.active());
			m.put("counter", p.counter());
			m.put("open", Database.count(
					"SELECT COUNT(*) FROM tickets WHERE panel_id = ? AND status = 'open'", p.id()));
			m.put("total", Database.count("SELECT COUNT(*) FROM tickets WHERE panel_id = ?", p.id()));
			aus.add(m);
		}
		return TicketApiServer.liste(aus, aus.size());
	}

	// -----------------------------------------------------------------------
	// /api/tickets und /api/tickets/{id}
	// -----------------------------------------------------------------------

	public static Object tickets(Anfrage anfrage) {
		final String rest = anfrage.pfad().substring("/api/tickets".length()).replace("/", "");
		return rest.isBlank() ? ticketListe(anfrage) : ticketDetail(anfrage, Long.parseLong(rest));
	}

	private static Object ticketListe(Anfrage anfrage) {
		final String guildId = guildIdVon(anfrage);
		final List<Panel> sichtbar = sichtbarePanels(guildId, anfrage.discordUserId());
		if (sichtbar.isEmpty()) {
			return TicketApiServer.liste(List.of(), 0);
		}

		final String status = anfrage.param("status", "");
		final String suche = anfrage.param("q", "");
		final int limit = Math.min(anfrage.intParam("limit", 50), 200);
		final int offset = Math.max(anfrage.intParam("offset", 0), 0);

		final StringBuilder wo = new StringBuilder("WHERE t.guild_id = ? AND t.panel_id IN (")
				.append(platzhalter(sichtbar.size())).append(')');
		final List<Object> werte = new ArrayList<>();
		werte.add(guildId);
		sichtbar.forEach(p -> werte.add(p.id()));

		if (!status.isBlank()) {
			wo.append(" AND t.status = ?");
			werte.add(status);
		}
		if (!suche.isBlank()) {
			wo.append(" AND (t.channel_name ILIKE ? OR t.owner_id = ?)");
			werte.add("%" + suche + "%");
			werte.add(suche);
		}

		final int gesamt = Database.count("SELECT COUNT(*) FROM tickets t " + wo, werte.toArray());

		final List<Object> mitSeite = new ArrayList<>(werte);
		mitSeite.add(limit);
		mitSeite.add(offset);

		final List<Map<String, Object>> zeilen = Database.query(
				"SELECT t.*, p.name AS panel_name FROM tickets t "
						+ "LEFT JOIN panels p ON p.id = t.panel_id " + wo
						+ " ORDER BY t.opened_at DESC LIMIT ? OFFSET ?",
				rs -> {
					final Map<String, Object> m = new LinkedHashMap<>();
					m.put("id", rs.getLong("id"));
					m.put("panel", rs.getString("panel_name"));
					m.put("number", rs.getInt("number"));
					m.put("channel_id", rs.getString("channel_id"));
					m.put("channel_name", rs.getString("channel_name"));
					m.put("owner_id", rs.getString("owner_id"));
					m.put("owner_name", name(guildId, rs.getString("owner_id")));
					m.put("claimed_by", rs.getString("claimed_by"));
					m.put("claimed_by_name", name(guildId, rs.getString("claimed_by")));
					m.put("status", rs.getString("status"));
					m.put("opened_at", String.valueOf(rs.getTimestamp("opened_at")));
					m.put("closed_at", String.valueOf(rs.getTimestamp("closed_at")));
					m.put("closed_by", rs.getString("closed_by"));
					return m;
				}, mitSeite.toArray());

		return TicketApiServer.liste(zeilen, gesamt);
	}

	/** Ein Ticket mit vollem Verlauf — der Ersatz fuer Ticket Tools HTML-Ansicht. */
	private static Object ticketDetail(Anfrage anfrage, long ticketId) {
		final Optional<Ticket> maybe = TicketDao.byId(ticketId);
		if (maybe.isEmpty()) {
			throw new SecurityException("unbekannt");
		}
		final Ticket ticket = maybe.get();

		if (!Visibility.darfSehen(guild(ticket.guildId()), ticket, anfrage.discordUserId())) {
			throw new SecurityException("nicht sichtbar");
		}

		final Map<String, Object> kopf = new LinkedHashMap<>();
		kopf.put("id", ticket.id());
		kopf.put("panel", ticket.panelId() == null ? null
				: PanelDao.byId(ticket.panelId()).map(Panel::name).orElse(null));
		kopf.put("number", ticket.number());
		kopf.put("channel_name", ticket.channelName());
		kopf.put("owner_id", ticket.ownerId());
		kopf.put("owner_name", name(ticket.guildId(), ticket.ownerId()));
		kopf.put("claimed_by", ticket.claimedBy());
		kopf.put("claimed_by_name", name(ticket.guildId(), ticket.claimedBy()));
		kopf.put("status", ticket.status().dbValue());
		kopf.put("opened_at", String.valueOf(ticket.openedAt()));
		kopf.put("closed_at", String.valueOf(ticket.closedAt()));
		kopf.put("closed_by", ticket.closedBy());
		kopf.put("close_reason", ticket.closeReason());
		kopf.put("members", TicketDao.members(ticket.id()));

		// Anhaenge werden je Nachricht zugeordnet, damit sie im Verlauf an der
		// richtigen Stelle stehen.
		final Map<Long, List<Map<String, Object>>> anhaenge = new HashMap<>();
		final List<Map<String, Object>> alleAnhaenge = new ArrayList<>();
		Database.query(
				"SELECT * FROM ticket_attachments WHERE ticket_id = ? ORDER BY id",
				rs -> {
					final Map<String, Object> a = new LinkedHashMap<>();
					a.put("filename", rs.getString("filename"));
					a.put("content_type", rs.getString("content_type"));
					a.put("size", rs.getLong("size_bytes"));
					a.put("storage_channel_id", rs.getString("storage_channel_id"));
					a.put("storage_message_id", rs.getString("storage_message_id"));
					anhaenge.computeIfAbsent(rs.getLong("ticket_message_id"),
							k -> new ArrayList<>()).add(a);
					alleAnhaenge.add(a);
					return null;
				}, ticket.id());

		// Die Adressen erst NACH der Abfrage holen: jede kostet einen Aufruf zu
		// Discord, und den waehrend einer offenen Datenbankverbindung zu machen
		// haelt eine Verbindung aus dem Pool fest, bis Discord antwortet.
		for (final Map<String, Object> a : alleAnhaenge) {
			a.put("url", AnhangUrl.frisch(
					String.valueOf(a.get("storage_channel_id")),
					String.valueOf(a.get("storage_message_id")),
					String.valueOf(a.get("filename"))));
		}

		final List<Map<String, Object>> verlauf = Database.query(
				"SELECT * FROM ticket_messages WHERE ticket_id = ? ORDER BY sent_at, id",
				rs -> {
					final Map<String, Object> m = new LinkedHashMap<>();
					final long zeilenId = rs.getLong("id");
					m.put("author_id", rs.getString("author_id"));
					m.put("author_name", rs.getString("author_name"));
					m.put("bot", rs.getBoolean("author_bot"));
					m.put("content", rs.getString("content"));
					m.put("sent_at", String.valueOf(rs.getTimestamp("sent_at")));
					m.put("edited", rs.getTimestamp("edited_at") != null);
					m.put("deleted", rs.getTimestamp("deleted_at") != null);
					m.put("attachments", anhaenge.getOrDefault(zeilenId, List.of()));
					return m;
				}, ticket.id());

		final Map<String, Object> antwort = new LinkedHashMap<>();
		antwort.put("ticket", kopf);
		antwort.put("messages", verlauf);
		return antwort;
	}

	// -----------------------------------------------------------------------
	// /api/legacy — die aus Ticket Tool uebernommenen Transcripts
	// -----------------------------------------------------------------------

	public static Object legacy(Anfrage anfrage) {
		final String guildId = guildIdVon(anfrage);
		final List<Panel> sichtbar = sichtbarePanels(guildId, anfrage.discordUserId());
		if (sichtbar.isEmpty()) {
			return TicketApiServer.liste(List.of(), 0);
		}

		// Alt-Transcripts kennen nur den Panel-NAMEN aus dem Ticket-Tool-Log,
		// keine Panel-ID. Deshalb wird ueber den Namen gefiltert; was sich nicht
		// zuordnen laesst, sehen nur Serververwalter.
		final Guild guild = guild(guildId);
		final Member member = Visibility.mitglied(guild, anfrage.discordUserId());
		final boolean allesSehen = member != null && Visibility.darfAllesSehen(member);

		final List<Object> werte = new ArrayList<>();
		werte.add(guildId);
		final StringBuilder wo = new StringBuilder("WHERE guild_id = ?");
		if (!allesSehen) {
			wo.append(" AND panel_name IN (").append(platzhalter(sichtbar.size())).append(')');
			sichtbar.forEach(p -> werte.add(p.name()));
		}

		final int gesamt = Database.count("SELECT COUNT(*) FROM legacy_transcripts " + wo, werte.toArray());

		final List<Object> mitSeite = new ArrayList<>(werte);
		mitSeite.add(Math.min(anfrage.intParam("limit", 50), 200));
		mitSeite.add(Math.max(anfrage.intParam("offset", 0), 0));

		final List<Map<String, Object>> zeilen = Database.query(
				"SELECT * FROM legacy_transcripts " + wo + " ORDER BY closed_at DESC LIMIT ? OFFSET ?",
				rs -> {
					final Map<String, Object> m = new LinkedHashMap<>();
					m.put("ticket_name", rs.getString("ticket_name"));
					m.put("panel", rs.getString("panel_name"));
					m.put("owner_id", rs.getString("owner_id"));
					m.put("closed_by", rs.getString("closed_by"));
					m.put("closed_at", String.valueOf(rs.getTimestamp("closed_at")));
					m.put("size", rs.getLong("size_bytes"));
					m.put("log_channel_id", rs.getString("log_channel_id"));
					m.put("log_message_id", rs.getString("log_message_id"));
					return m;
				}, mitSeite.toArray());

		return TicketApiServer.liste(zeilen, gesamt);
	}

	// -----------------------------------------------------------------------
	// /api/stats
	// -----------------------------------------------------------------------

	public static Object stats(Anfrage anfrage) {
		final String guildId = guildIdVon(anfrage);
		final List<Panel> sichtbar = sichtbarePanels(guildId, anfrage.discordUserId());
		final Map<String, Object> m = new LinkedHashMap<>();

		if (sichtbar.isEmpty()) {
			m.put("visible", false);
			return m;
		}

		final List<Object> ids = new ArrayList<>();
		sichtbar.forEach(p -> ids.add(p.id()));
		final String inPanels = " panel_id IN (" + platzhalter(sichtbar.size()) + ")";

		m.put("visible", true);
		m.put("panels", sichtbar.size());
		m.put("open", Database.count(
				"SELECT COUNT(*) FROM tickets WHERE status = 'open' AND" + inPanels, ids.toArray()));
		m.put("closed", Database.count(
				"SELECT COUNT(*) FROM tickets WHERE status = 'closed' AND" + inPanels, ids.toArray()));
		m.put("unclaimed", Database.count(
				"SELECT COUNT(*) FROM tickets WHERE status = 'open' AND claimed_by IS NULL AND"
						+ inPanels, ids.toArray()));
		m.put("legacy", Database.count(
				"SELECT COUNT(*) FROM legacy_transcripts WHERE guild_id = ?", guildId));

		// Verlauf der letzten 30 Tage, fuer die Kurve im Dashboard.
		final List<Object> proTag = new ArrayList<>(Database.query(
				"SELECT date_trunc('day', opened_at) AS tag, COUNT(*) AS anzahl FROM tickets "
						+ "WHERE opened_at > now() - interval '30 days' AND" + inPanels
						+ " GROUP BY tag ORDER BY tag",
				rs -> {
					final Map<String, Object> t = new LinkedHashMap<>();
					t.put("day", String.valueOf(rs.getTimestamp("tag")).substring(0, 10));
					t.put("count", rs.getInt("anzahl"));
					return (Object) t;
				}, ids.toArray()));
		m.put("per_day", proTag);

		return m;
	}

	/**
	 * Ein Anzeigename zur Discord-ID, soweit der Bot ihn kennt.
	 *
	 * Nur aus dem Cache — der ist wegen {@code MemberCachePolicy.ALL}
	 * vollstaendig, und eine Abfrage bei Discord je Zeile wuerde eine Liste mit
	 * fuenfzig Tickets in fuenfzig Aufrufe verwandeln. Wer nicht mehr auf dem
	 * Server ist, bleibt {@code null}; die Website zeigt dann die ID.
	 */
	private static String name(String guildId, String userId) {
		if (userId == null || Bot.jda() == null) {
			return null;
		}
		final Guild guild = Bot.jda().getGuildById(guildId);
		if (guild == null) {
			return null;
		}
		final Member member = guild.getMemberById(userId);
		return member == null ? null : member.getEffectiveName();
	}

	private static String platzhalter(int anzahl) {
		return String.join(",", java.util.Collections.nCopies(anzahl, "?"));
	}
}
