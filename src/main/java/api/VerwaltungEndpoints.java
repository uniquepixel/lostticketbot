package api;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import api.TicketApiServer.Anfrage;
import commands.PanelCommand;
import db.Database;
import db.FrageDao;
import db.GuildConfigDao;
import db.MenuDao;
import db.PanelDao;
import lostticketbot.Bot;
import model.Frage;
import model.GuildConfig;
import model.Menu;
import model.MenuEntry;
import model.Panel;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import ticket.Visibility;
import verwaltung.Verwaltung;
import verwaltung.Verwaltung.Fehler;

/**
 * Die Ticket-Verwaltung der Website: Panels, Menüs, Rollen, Fragen.
 *
 * <pre>
 * GET  /api/verwaltung/guilds          Server, die der Anfragende verwalten darf
 * GET  /api/verwaltung?guild=…         alles, was die Verwaltungsseite braucht
 * POST /api/verwaltung/&lt;aktion&gt;        eine Änderung, Körper mit "guild"
 * </pre>
 *
 * Das Recht wird bei JEDEM Aufruf hier geprüft, gegen die echten Discord-Rechte
 * des Anfragenden auf genau diesem Server (dieselbe Schwelle wie die
 * Slash-Befehle: „Server verwalten“) — oder er ist Website-Admin, was das
 * Website-Backend aus dem signierten Anmeldetoken setzt. Die Website selbst
 * entscheidet nichts. Die eigentlichen Änderungen macht {@link Verwaltung},
 * dieselbe Klasse, die auch die Slash-Befehle benutzen.
 */
public final class VerwaltungEndpoints {

	private static final String QUELLE = "Website";

	private VerwaltungEndpoints() {
	}

	public static Object bearbeiten(Anfrage a) {
		final String rest = a.pfad().substring("/api/verwaltung".length()).replaceAll("^/+|/+$", "");
		if ("GET".equals(a.methode())) {
			return rest.equals("guilds") ? guilds(a) : uebersicht(a, guild(a.param("guild")));
		}
		final Guild guild = guild(text(a.koerper(), "guild"));
		Verwaltung.pruefeRecht(guild, a.discordUserId(), a.websiteAdmin());
		final String meldung = schreiben(rest, guild, a);
		Verwaltung.protokoll(guild, a.discordUserId(), QUELLE + (a.websiteAdmin() ? ", Website-Admin" : ""), meldung);
		final Map<String, Object> aus = new LinkedHashMap<>();
		aus.put("ok", true);
		aus.put("meldung", meldung);
		return aus;
	}

	// -----------------------------------------------------------------------
	// Lesen
	// -----------------------------------------------------------------------

	private static Object guilds(Anfrage a) {
		final List<Map<String, Object>> aus = new ArrayList<>();
		if (Bot.jda() != null) {
			for (final Guild g : Bot.jda().getGuilds()) {
				if (!a.websiteAdmin() && !Verwaltung.darfVerwalten(Visibility.mitglied(g, a.discordUserId()))) {
					continue;
				}
				final Map<String, Object> m = new LinkedHashMap<>();
				m.put("id", g.getId());
				m.put("name", g.getName());
				m.put("icon", g.getIconUrl());
				aus.add(m);
			}
		}
		return TicketApiServer.liste(aus, aus.size());
	}

	private static Object uebersicht(Anfrage a, Guild guild) {
		Verwaltung.pruefeRecht(guild, a.discordUserId(), a.websiteAdmin());
		final Map<String, Object> aus = new LinkedHashMap<>();

		final Map<String, Object> g = new LinkedHashMap<>();
		g.put("id", guild.getId());
		g.put("name", guild.getName());
		g.put("icon", guild.getIconUrl());
		aus.put("guild", g);

		final GuildConfig c = GuildConfigDao.get(guild.getId());
		final Map<String, Object> config = new LinkedHashMap<>();
		config.put("limit", c.globalMaxOpenTickets());
		config.put("panel_color", c.panelEmbedColor());
		config.put("ticket_color", c.ticketEmbedColor());
		aus.put("config", config);

		final List<Map<String, Object>> panels = new ArrayList<>();
		for (final Panel p : PanelDao.byGuild(guild.getId())) {
			panels.add(panelJson(p));
		}
		aus.put("panels", panels);

		final List<Map<String, Object>> menus = new ArrayList<>();
		for (final Menu m : MenuDao.byGuild(guild.getId())) {
			menus.add(menuJson(guild, m));
		}
		aus.put("menus", menus);

		// Rollen von oben nach unten wie in Discord; nur lesen, nie umsortieren.
		final List<Map<String, Object>> rollen = new ArrayList<>();
		for (final Role r : guild.getRoles()) {
			if (r.isPublicRole()) {
				continue;
			}
			final Map<String, Object> m = new LinkedHashMap<>();
			m.put("id", r.getId());
			m.put("name", r.getName());
			m.put("color", r.getColorRaw());
			m.put("managed", r.isManaged());
			rollen.add(m);
		}
		aus.put("rollen", rollen);

		final List<Map<String, Object>> kategorien = new ArrayList<>();
		for (final Category k : guild.getCategories()) {
			final Map<String, Object> m = new LinkedHashMap<>();
			m.put("id", k.getId());
			m.put("name", k.getName());
			kategorien.add(m);
		}
		aus.put("kategorien", kategorien);

		final List<Map<String, Object>> kanaele = new ArrayList<>();
		final List<TextChannel> texte = new ArrayList<>(guild.getTextChannels());
		texte.sort(Comparator
				.comparingInt((TextChannel t) -> t.getParentCategory() == null ? -1
						: t.getParentCategory().getPositionRaw())
				.thenComparingInt(TextChannel::getPositionRaw));
		for (final TextChannel t : texte) {
			final Map<String, Object> m = new LinkedHashMap<>();
			m.put("id", t.getId());
			m.put("name", t.getName());
			m.put("kategorie_id", t.getParentCategoryId());
			m.put("kategorie", t.getParentCategory() == null ? null : t.getParentCategory().getName());
			kanaele.add(m);
		}
		aus.put("kanaele", kanaele);

		final List<Map<String, Object>> sperren = Database.query(
				"SELECT subject_id, is_role, reason, added_by, added_at FROM blacklist WHERE guild_id = ? "
						+ "ORDER BY added_at DESC",
				rs -> {
					final Map<String, Object> m = new LinkedHashMap<>();
					m.put("subject_id", rs.getString("subject_id"));
					m.put("is_role", rs.getBoolean("is_role"));
					m.put("reason", rs.getString("reason"));
					m.put("added_by", rs.getString("added_by"));
					m.put("added_at", String.valueOf(rs.getTimestamp("added_at")));
					return m;
				}, guild.getId());
		for (final Map<String, Object> m : sperren) {
			final String id = (String) m.get("subject_id");
			if (Boolean.TRUE.equals(m.get("is_role"))) {
				final Role r = guild.getRoleById(id);
				m.put("name", r == null ? null : r.getName());
			} else {
				final Member mem = guild.getMemberById(id);
				m.put("name", mem == null ? null : mem.getUser().getName());
			}
		}
		aus.put("sperrliste", sperren);

		final List<Map<String, Object>> felder = new ArrayList<>();
		for (final PanelCommand.Field f : PanelCommand.Field.values()) {
			felder.add(Map.of("name", f.name(), "art", f.kind.name()));
		}
		aus.put("felder", felder);
		return aus;
	}

	static Map<String, Object> panelJson(Panel p) {
		final Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", p.id());
		m.put("name", p.name());
		m.put("active", p.active());
		m.put("category_opened", p.categoryOpened());
		m.put("category_closed", p.categoryClosed());
		m.put("name_pattern_open", p.namePatternOpen());
		m.put("name_pattern_closed", p.namePatternClosed());
		m.put("counter", p.counter());
		m.put("counter_padding", p.counterPadding());
		m.put("welcome_text", p.welcomeText());
		m.put("welcome_embed_title", p.welcomeEmbedTitle());
		m.put("welcome_embed_text", p.welcomeEmbedText());
		m.put("log_channel_id", p.logChannelId());
		m.put("max_open_per_user", p.maxOpenPerUser());
		m.put("cooldown_seconds", p.cooldownSeconds());
		m.put("close_on_owner_leave", p.closeOnOwnerLeave());
		m.put("owner_leave_message", p.ownerLeaveMessage());
		m.put("dm_on_open", p.dmOnOpen());
		m.put("dm_on_close", p.dmOnClose());
		m.put("next_name", p.renderName(false, p.counter() + 1, "name"));
		m.put("support", PanelDao.supportRoles(p.id()));
		m.put("ping", PanelDao.pingRoles(p.id()));
		final List<Map<String, Object>> fragen = new ArrayList<>();
		for (final Frage f : FrageDao.vonPanel(p.id())) {
			final Map<String, Object> fm = new LinkedHashMap<>();
			fm.put("label", f.label());
			fm.put("placeholder", f.placeholder());
			fm.put("lang", f.langeAntwort());
			fm.put("pflicht", f.pflicht());
			fm.put("max", f.maxLaenge());
			fragen.add(fm);
		}
		m.put("fragen", fragen);
		m.put("open", Database.count("SELECT COUNT(*) FROM tickets WHERE panel_id = ? AND status = 'open'", p.id()));
		m.put("total", Database.count("SELECT COUNT(*) FROM tickets WHERE panel_id = ?", p.id()));
		return m;
	}

	private static Map<String, Object> menuJson(Guild guild, Menu menu) {
		final Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", menu.id());
		m.put("channel_id", menu.channelId());
		final TextChannel k = guild.getTextChannelById(menu.channelId());
		m.put("channel_name", k == null ? null : k.getName());
		m.put("message_id", menu.messageId());
		m.put("posted", menu.isPosted());
		m.put("style", menu.style() == Menu.Style.SELECT ? "dropdown" : "knoepfe");
		m.put("embed_title", menu.embedTitle());
		m.put("embed_text", menu.embedText());
		m.put("embed_color", menu.embedColor());
		m.put("placeholder", menu.placeholder());
		final List<Map<String, Object>> eintraege = new ArrayList<>();
		for (final MenuEntry e : MenuDao.entries(menu.id())) {
			final Map<String, Object> em = new LinkedHashMap<>();
			em.put("panel_id", e.panelId());
			em.put("label", e.label());
			em.put("description", e.description());
			em.put("emoji", e.emoji());
			em.put("button_style", e.buttonStyle());
			em.put("position", e.position());
			eintraege.add(em);
		}
		m.put("entries", eintraege);
		return m;
	}

	// -----------------------------------------------------------------------
	// Schreiben
	// -----------------------------------------------------------------------

	/** Führt eine Aktion aus und gibt den Satz zurück, der ins Protokoll und an den Nutzer geht. */
	static String schreiben(String aktion, Guild guild, Anfrage a) {
		final Map<String, Object> k = a.koerper();
		final String gid = guild.getId();
		switch (aktion) {
			case "panel/erstellen" -> {
				final Panel p = Verwaltung.panelErstellen(gid, text(k, "name"));
				return "Panel „" + p.name() + "“ angelegt (ID " + p.id() + ")";
			}
			case "panel/setzen" -> {
				final Panel p = Verwaltung.panel(gid, zahl(k, "panel"));
				final Map<String, String> roh = new LinkedHashMap<>();
				if (!(k.get("felder") instanceof Map<?, ?> felder) || felder.isEmpty()) {
					throw new Fehler("Keine Felder angegeben.");
				}
				for (final var e : felder.entrySet()) {
					roh.put(String.valueOf(e.getKey()), alsText(e.getValue()));
				}
				final Map<String, Object> gesetzt = Verwaltung.panelFelderSetzen(guild, p, roh);
				final List<String> teile = new ArrayList<>();
				gesetzt.forEach((f, w) -> teile.add(f.toLowerCase() + " = " + kurz(w)));
				return "Panel „" + p.name() + "“: " + String.join(", ", teile);
			}
			case "panel/rolle" -> {
				final Panel p = Verwaltung.panel(gid, zahl(k, "panel"));
				final String rolle = text(k, "rolle");
				final String art = textOder(k, "art", "support");
				final boolean entfernen = Boolean.TRUE.equals(k.get("entfernen"));
				Verwaltung.panelRolle(guild, p, rolle, art, entfernen);
				final Role r = guild.getRoleById(rolle);
				return "Panel „" + p.name() + "“: Rolle " + (r == null ? rolle : r.getName()) + " (" + art + ") "
						+ (entfernen ? "entfernt" : "hinzugefügt");
			}
			case "panel/fragen" -> {
				final Panel p = Verwaltung.panel(gid, zahl(k, "panel"));
				final List<Verwaltung.FrageEingabe> eingaben = new ArrayList<>();
				if (k.get("fragen") instanceof List<?> liste) {
					for (final Object o : liste) {
						if (!(o instanceof Map<?, ?> f)) {
							throw new Fehler("Fragen müssen Objekte sein.");
						}
						@SuppressWarnings("unchecked")
						final Map<String, Object> fm = (Map<String, Object>) f;
						eingaben.add(new Verwaltung.FrageEingabe(
								textOder(fm, "label", ""), textOder(fm, "placeholder", null),
								Boolean.TRUE.equals(fm.get("lang")), !Boolean.FALSE.equals(fm.get("pflicht")),
								fm.get("max") == null || "".equals(fm.get("max")) ? null : zahlInt(fm, "max")));
					}
				}
				final List<Frage> neu = Verwaltung.fragenSetzen(p, eingaben);
				return "Panel „" + p.name() + "“: " + (neu.isEmpty() ? "Formular entfernt"
						: neu.size() + " Formularfrage" + (neu.size() == 1 ? "" : "n") + " gespeichert");
			}
			case "menu/erstellen" -> {
				final Menu m = Verwaltung.menuErstellen(guild, text(k, "kanal"), Verwaltung.stil(textOder(k, "stil", null)));
				return "Menü " + m.id() + " angelegt in #" + kanalName(guild, m.channelId()) + " (noch nicht gepostet)";
			}
			case "menu/text" -> {
				final Menu m = Verwaltung.menu(gid, zahl(k, "menu"));
				final Integer farbe = k.get("farbe") == null || "".equals(k.get("farbe")) ? null : farbe(k.get("farbe"));
				Verwaltung.menuText(m, textOder(k, "titel", null), textOder(k, "text", null),
						textOder(k, "platzhalter", null), farbe);
				return "Menü " + m.id() + ": Titel/Text geändert";
			}
			case "menu/stil" -> {
				final Menu m = Verwaltung.menu(gid, zahl(k, "menu"));
				final Menu.Style s = Verwaltung.stil(text(k, "stil"));
				Verwaltung.menuStil(m, s);
				return "Menü " + m.id() + ": Stil " + (s == Menu.Style.SELECT ? "Dropdown" : "Knöpfe");
			}
			case "menu/kanal" -> {
				final Menu m = Verwaltung.menu(gid, zahl(k, "menu"));
				Verwaltung.menuKanal(guild, m, text(k, "kanal"));
				return "Menü " + m.id() + " zieht nach #" + kanalName(guild, text(k, "kanal"))
						+ (m.isPosted() ? " (alte Nachricht gelöscht, neu posten nötig)" : "");
			}
			case "menu/eintrag" -> {
				final Menu m = Verwaltung.menu(gid, zahl(k, "menu"));
				final Panel p = Verwaltung.panel(gid, zahl(k, "panel"));
				Verwaltung.menuEintrag(m, p, textOder(k, "label", null), textOder(k, "emoji", null),
						textOder(k, "beschreibung", null),
						k.get("knopfstil") == null ? null : zahlInt(k, "knopfstil"),
						k.get("position") == null ? null : zahlInt(k, "position"));
				return "Menü " + m.id() + ": Eintrag für Panel „" + p.name() + "“ gespeichert";
			}
			case "menu/eintrag-entfernen" -> {
				final Menu m = Verwaltung.menu(gid, zahl(k, "menu"));
				final Panel p = Verwaltung.panel(gid, zahl(k, "panel"));
				Verwaltung.menuEintragEntfernen(m, p);
				return "Menü " + m.id() + ": Panel „" + p.name() + "“ herausgenommen";
			}
			case "menu/reihenfolge" -> {
				final Menu m = Verwaltung.menu(gid, zahl(k, "menu"));
				final List<Long> ids = new ArrayList<>();
				if (k.get("panels") instanceof List<?> liste) {
					for (final Object o : liste) {
						ids.add(Long.parseLong(String.valueOf(o)));
					}
				}
				Verwaltung.menuReihenfolge(m, ids);
				return "Menü " + m.id() + ": Reihenfolge geändert";
			}
			case "menu/posten" -> {
				final Menu m = Verwaltung.menu(gid, zahl(k, "menu"));
				final boolean warGepostet = m.isPosted();
				Verwaltung.menuPosten(guild, m);
				return "Menü " + m.id() + (warGepostet ? " in Discord abgeglichen" : " gepostet") + " (#"
						+ kanalName(guild, m.channelId()) + ")";
			}
			case "menu/loeschen" -> {
				final Menu m = Verwaltung.menu(gid, zahl(k, "menu"));
				Verwaltung.menuLoeschen(guild, m);
				return "Menü " + m.id() + " gelöscht" + (m.isPosted() ? " samt Nachricht in #" + kanalName(guild, m.channelId()) : "");
			}
			case "menus/abgleichen" -> {
				final int n = Verwaltung.alleAbgleichen(guild);
				return n + " gepostete" + (n == 1 ? "s Menü" : " Menüs") + " in Discord abgeglichen";
			}
			case "config/limit" -> {
				final int n = zahlInt(k, "anzahl");
				Verwaltung.limitSetzen(gid, n);
				return "Serverweites Limit offener Tickets = " + (n == 0 ? "unbegrenzt" : n);
			}
			case "sperre/hinzufuegen" -> {
				final boolean rolle = Boolean.TRUE.equals(k.get("rolle"));
				Verwaltung.sperren(gid, text(k, "wer"), rolle, textOder(k, "grund", null), a.discordUserId());
				return (rolle ? "Rolle " : "Person ") + text(k, "wer") + " gesperrt";
			}
			case "sperre/entfernen" -> {
				Verwaltung.entsperren(gid, text(k, "wer"));
				return "Sperre für " + text(k, "wer") + " aufgehoben";
			}
			default -> throw new Fehler("Unbekannte Aktion: " + aktion);
		}
	}

	// -----------------------------------------------------------------------
	// Hilfen
	// -----------------------------------------------------------------------

	private static Guild guild(String guildId) {
		if (guildId == null || guildId.isBlank()) {
			throw new Fehler("Parameter 'guild' fehlt");
		}
		final Guild g = Bot.jda() == null ? null : Bot.jda().getGuildById(guildId);
		if (g == null) {
			throw new Fehler("Unbekannter Server: " + guildId);
		}
		return g;
	}

	private static String kanalName(Guild g, String id) {
		final TextChannel t = id == null ? null : g.getTextChannelById(id.replaceAll("\\D", ""));
		return t == null ? String.valueOf(id) : t.getName();
	}

	static String text(Map<String, Object> k, String feld) {
		final Object o = k.get(feld);
		if (o == null || String.valueOf(o).isBlank()) {
			throw new Fehler("Feld '" + feld + "' fehlt.");
		}
		return String.valueOf(o).trim();
	}

	static String textOder(Map<String, Object> k, String feld, String standard) {
		final Object o = k.get(feld);
		return o == null ? standard : String.valueOf(o);
	}

	static long zahl(Map<String, Object> k, String feld) {
		try {
			return Long.parseLong(text(k, feld));
		} catch (final NumberFormatException e) {
			throw new Fehler("Feld '" + feld + "' muss eine Zahl sein.");
		}
	}

	static int zahlInt(Map<String, Object> k, String feld) {
		final long z = zahl(k, feld);
		if (z < Integer.MIN_VALUE || z > Integer.MAX_VALUE) {
			throw new Fehler("Feld '" + feld + "' ist zu groß.");
		}
		return (int) z;
	}

	/** Farbe als Zahl oder als „#1ec45c“/„1ec45c“. */
	static Integer farbe(Object o) {
		if (o instanceof Number n) {
			return n.intValue();
		}
		final String s = String.valueOf(o).trim().replace("#", "");
		try {
			return Integer.parseInt(s, 16);
		} catch (final NumberFormatException e) {
			throw new Fehler("„" + o + "“ ist keine Farbe (erwartet z. B. #1ec45c).");
		}
	}

	/** Werte aus JSON in die Textform, die {@link Verwaltung#panelFelderSetzen} versteht. */
	static String alsText(Object o) {
		if (o == null) {
			return "";
		}
		if (o instanceof Boolean b) {
			return b ? "ja" : "nein";
		}
		return String.valueOf(o);
	}

	private static String kurz(Object w) {
		final String s = w == null ? "—" : String.valueOf(w).replace('\n', ' ');
		return s.length() > 60 ? s.substring(0, 60) + "…" : s;
	}
}
