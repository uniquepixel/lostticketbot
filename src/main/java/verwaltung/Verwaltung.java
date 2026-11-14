package verwaltung;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import commands.PanelCommand;
import db.Database;
import db.FrageDao;
import db.GuildConfigDao;
import db.MenuDao;
import db.PanelDao;
import model.Frage;
import model.Menu;
import model.MenuEntry;
import model.Panel;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import panel.MenuRenderer;
import ticket.Visibility;

/**
 * Die Konfiguration des Ticket-Bots ändern — eine Stelle für beide Wege.
 *
 * Die Slash-Befehle ({@code /panel}, {@code /menu}, {@code /ticketconfig}) und
 * die Verwaltung auf der Website rufen dieselben Methoden. Zwei getrennte
 * Implementierungen wären genau die Art von Doppelung, bei der die eine
 * irgendwann großzügiger prüft als die andere.
 *
 * Jede Methode prüft ihre Eingaben und wirft bei Unfug eine {@link Fehler} mit
 * einem Satz, den man dem Nutzer so zeigen kann. Wer handeln darf, prüft
 * {@link #darfVerwalten} — die Slash-Befehle verlassen sich zusätzlich auf
 * Discords Befehlsrechte.
 *
 * Jede Änderung geht über {@link #protokoll} in die Konsole und damit in den
 * Log-Kanal {@code 💻┋ticket-bot-log}.
 */
public final class Verwaltung {

	/** Eine Eingabe passt nicht. Die Nachricht ist für den Nutzer gedacht. */
	public static final class Fehler extends IllegalArgumentException {
		private static final long serialVersionUID = 1L;

		public Fehler(String nachricht) {
			super(nachricht);
		}
	}

	/** Wer handelt, darf das nicht. */
	public static final class KeinRecht extends RuntimeException {
		private static final long serialVersionUID = 1L;

		public KeinRecht(String nachricht) {
			super(nachricht);
		}
	}

	/** Discord: höchstens 25 Knöpfe (5×5) bzw. 25 Dropdown-Optionen je Nachricht. */
	public static final int MAX_EINTRAEGE = 25;
	/** Discord: höchstens fünf Felder je Formular. */
	public static final int MAX_FRAGEN = 5;

	private Verwaltung() {
	}

	// -----------------------------------------------------------------------
	// Rechte
	// -----------------------------------------------------------------------

	/**
	 * Darf dieses Mitglied die Ticket-Konfiguration ändern?
	 *
	 * Dieselbe Schwelle wie bei den Slash-Befehlen: sie sind mit
	 * {@code MANAGE_SERVER} angemeldet. Eigentümer und Administratoren haben das
	 * ohnehin. Website-Admins kommen über {@link #pruefeRecht} dazu.
	 */
	public static boolean darfVerwalten(Member member) {
		return member != null && Visibility.darfAllesSehen(member);
	}

	/**
	 * Wirft {@link KeinRecht}, wenn {@code userId} hier nichts ändern darf.
	 *
	 * @param websiteAdmin vom Website-Backend aus dem signierten Anmeldetoken
	 *                     gesetzt, nie aus einer Eingabe des Browsers
	 */
	public static void pruefeRecht(Guild guild, String userId, boolean websiteAdmin) {
		if (websiteAdmin) {
			return;
		}
		if (!darfVerwalten(Visibility.mitglied(guild, userId))) {
			throw new KeinRecht("Für die Ticket-Verwaltung auf " + guild.getName()
					+ " brauchst du dort das Recht „Server verwalten“.");
		}
	}

	// -----------------------------------------------------------------------
	// Panels
	// -----------------------------------------------------------------------

	public static Panel panelErstellen(String guildId, String name) {
		final String n = name == null ? "" : name.trim();
		if (n.isEmpty()) {
			throw new Fehler("Der Name darf nicht leer sein.");
		}
		if (n.length() > 80) {
			throw new Fehler("Der Name ist zu lang (höchstens 80 Zeichen).");
		}
		if (PanelDao.byName(guildId, n).isPresent()) {
			throw new Fehler("Ein Panel namens „" + n + "“ gibt es schon.");
		}
		final long id = PanelDao.create(guildId, n);
		return PanelDao.byId(id).orElseThrow();
	}

	public static Panel panel(String guildId, long panelId) {
		return PanelDao.byId(panelId).filter(p -> p.guildId().equals(guildId))
				.orElseThrow(() -> new Fehler("Kein Panel mit der ID " + panelId + " auf diesem Server."));
	}

	public static Panel panelNachName(String guildId, String name) {
		return PanelDao.byName(guildId, name)
				.orElseThrow(() -> new Fehler("Kein Panel namens „" + name + "“."));
	}

	public static PanelCommand.Field feld(String roh) {
		for (final PanelCommand.Field f : PanelCommand.Field.values()) {
			if (f.name().equalsIgnoreCase(roh) || f.column.equalsIgnoreCase(roh)) {
				return f;
			}
		}
		throw new Fehler("Unbekanntes Feld: " + roh);
	}

	/**
	 * Setzt ein Feld eines Panels und gibt den gespeicherten Wert zurück.
	 *
	 * {@code roh} ist Text wie aus dem Slash-Befehl: leer oder „-“ löscht
	 * Textfelder, Wahrheitswerte als ja/nein.
	 */
	public static Object panelFeldSetzen(Guild guild, Panel panel, PanelCommand.Field feld, String roh) {
		return panelFelderSetzen(guild, panel, java.util.Map.of(feld.name(), roh == null ? "" : roh))
				.get(feld.name());
	}

	/**
	 * Setzt mehrere Felder auf einmal — erst alle prüfen, dann alle schreiben.
	 * Sonst bliebe bei einem Fehler im dritten Feld ein halb geändertes Panel
	 * zurück.
	 *
	 * @return Feldname → gespeicherter Wert
	 */
	public static java.util.Map<String, Object> panelFelderSetzen(Guild guild, Panel panel,
			java.util.Map<String, String> roh) {
		final java.util.Map<PanelCommand.Field, Object> geprueft = new java.util.LinkedHashMap<>();
		for (final var e : roh.entrySet()) {
			final PanelCommand.Field f = feld(e.getKey());
			final String r = e.getValue() == null ? "" : e.getValue().trim();
			final Object wert;
			try {
				wert = switch (f.kind) {
					case NUMBER -> Integer.parseInt(r);
					case BOOL -> parseBool(r);
					case TEXT -> r.isEmpty() || "-".equals(r) ? null : r;
				};
			} catch (final IllegalArgumentException ex) {
				throw new Fehler("„" + r + "“ passt nicht zum Feld " + f.name().toLowerCase() + ".");
			}
			pruefeFeld(guild, f, wert);
			geprueft.put(f, wert);
		}
		final java.util.Map<String, Object> aus = new java.util.LinkedHashMap<>();
		for (final var e : geprueft.entrySet()) {
			PanelDao.set(panel.id(), e.getKey().column, e.getValue());
			aus.put(e.getKey().name(), e.getValue());
		}
		return aus;
	}

	private static void pruefeFeld(Guild guild, PanelCommand.Field feld, Object wert) {
		switch (feld) {
			case KATEGORIE_OFFEN, KATEGORIE_GESCHLOSSEN -> {
				if (wert != null) {
					final Category c = guild.getCategoryById(idOderFehler((String) wert, "Kategorie"));
					if (c == null) {
						throw new Fehler("Auf diesem Server gibt es keine Kategorie mit der ID " + wert + ".");
					}
				}
			}
			case LOG_KANAL -> {
				if (wert != null && guild.getTextChannelById(idOderFehler((String) wert, "Kanal")) == null) {
					throw new Fehler("Auf diesem Server gibt es keinen Textkanal mit der ID " + wert + ".");
				}
			}
			case NAME_OFFEN, NAME_GESCHLOSSEN -> {
				if (wert == null) {
					throw new Fehler("Ein Namensmuster darf nicht leer sein.");
				}
				if (((String) wert).length() > 90) {
					throw new Fehler("Das Namensmuster ist zu lang (höchstens 90 Zeichen).");
				}
			}
			case ZAEHLER -> zahlZwischen(wert, 0, 1_000_000, "Der Zähler");
			case STELLEN -> zahlZwischen(wert, 0, 8, "Die Stellenzahl");
			case MAX_OFFEN -> zahlZwischen(wert, 0, 100, "Das Limit");
			case COOLDOWN -> zahlZwischen(wert, 0, 30 * 24 * 3600, "Der Cooldown");
			case WILLKOMMENSTEXT -> laenge(wert, 1800, "Der Willkommenstext");
			case EMBED_TITEL -> laenge(wert, 256, "Der Titel");
			case EMBED_TEXT -> laenge(wert, 4000, "Der Embed-Text");
			case WEGGEGANGEN_TEXT -> laenge(wert, 1800, "Der Text");
			default -> {
			}
		}
	}

	public static void panelRolle(Guild guild, Panel panel, String roleId, String art, boolean entfernen) {
		final String kind = art == null || art.isBlank() ? "support" : art.trim().toLowerCase();
		if (!"support".equals(kind) && !"ping".equals(kind)) {
			throw new Fehler("Art muss „support“ oder „ping“ sein.");
		}
		final String id = idOderFehler(roleId, "Rolle");
		if (entfernen) {
			// Entfernen geht auch für Rollen, die es auf Discord nicht mehr gibt —
			// sonst bliebe eine gelöschte Rolle für immer in der Liste.
			PanelDao.removeRole(panel.id(), id, kind);
			return;
		}
		final Role rolle = guild.getRoleById(id);
		if (rolle == null) {
			throw new Fehler("Auf diesem Server gibt es keine Rolle mit der ID " + id + ".");
		}
		if (rolle.isPublicRole()) {
			// @everyone als Support-Rolle machte jedes Ticket des Panels für alle
			// lesbar, als Ping-Rolle pingte es den ganzen Server.
			throw new Fehler("@everyone geht hier nicht.");
		}
		PanelDao.addRole(panel.id(), id, kind);
	}

	/** Eine Frage, wie sie von außen kommt — vor der Prüfung. */
	public record FrageEingabe(String label, String placeholder, boolean langeAntwort, boolean pflicht,
			Integer maxLaenge) {
	}

	public static List<Frage> fragenSetzen(Panel panel, List<FrageEingabe> eingaben) {
		if (eingaben.size() > MAX_FRAGEN) {
			throw new Fehler("Höchstens " + MAX_FRAGEN + " Fragen — mehr Felder hat ein Discord-Formular nicht.");
		}
		final List<Frage> fragen = new ArrayList<>();
		int i = 0;
		for (final FrageEingabe e : eingaben) {
			i++;
			final String label = e.label() == null ? "" : e.label().trim();
			if (label.isEmpty()) {
				throw new Fehler("Frage " + i + " hat keinen Text.");
			}
			if (label.length() > 45) {
				throw new Fehler("Frage " + i + " ist zu lang (Discord erlaubt 45 Zeichen).");
			}
			final String ph = e.placeholder() == null || e.placeholder().isBlank() ? null : e.placeholder().trim();
			if (ph != null && ph.length() > 100) {
				throw new Fehler("Der Platzhalter von Frage " + i + " ist zu lang (höchstens 100 Zeichen).");
			}
			if (e.maxLaenge() != null && (e.maxLaenge() < 1 || e.maxLaenge() > 4000)) {
				throw new Fehler("Die Höchstlänge von Frage " + i + " muss zwischen 1 und 4000 liegen.");
			}
			fragen.add(new Frage(0, panel.id(), i - 1, label, ph, e.langeAntwort(), e.pflicht(), e.maxLaenge()));
		}
		FrageDao.ersetzen(panel.id(), fragen);
		return FrageDao.vonPanel(panel.id());
	}

	// -----------------------------------------------------------------------
	// Menüs
	// -----------------------------------------------------------------------

	public static Menu menu(String guildId, long menuId) {
		return MenuDao.byId(menuId).filter(m -> m.guildId().equals(guildId))
				.orElseThrow(() -> new Fehler("Kein Menü mit der ID " + menuId + " auf diesem Server."));
	}

	public static Menu.Style stil(String roh) {
		if (roh == null || roh.isBlank()) {
			return Menu.Style.BUTTONS;
		}
		return switch (roh.trim().toLowerCase()) {
			case "dropdown", "select" -> Menu.Style.SELECT;
			case "knoepfe", "knöpfe", "buttons" -> Menu.Style.BUTTONS;
			default -> throw new Fehler("Stil muss „knoepfe“ oder „dropdown“ sein.");
		};
	}

	private static TextChannel textkanal(Guild guild, String channelId) {
		final TextChannel kanal = guild.getTextChannelById(idOderFehler(channelId, "Kanal"));
		if (kanal == null) {
			throw new Fehler("Auf diesem Server gibt es keinen Textkanal mit der ID " + channelId + ".");
		}
		return kanal;
	}

	public static Menu menuErstellen(Guild guild, String channelId, Menu.Style stil) {
		final TextChannel kanal = textkanal(guild, channelId);
		final long id = MenuDao.create(guild.getId(), kanal.getId(), stil);
		return MenuDao.byId(id).orElseThrow();
	}

	public static void menuText(Menu menu, String titel, String text, String platzhalter, Integer farbe) {
		final String t = leerZuNull(titel);
		final String b = leerZuNull(text);
		final String p = leerZuNull(platzhalter);
		laenge(t, 256, "Der Titel");
		laenge(b, 4000, "Der Text");
		laenge(p, 150, "Der Platzhalter");
		if (t == null && b == null) {
			throw new Fehler("Ein Menü braucht einen Titel oder einen Text.");
		}
		if (farbe != null && (farbe < 0 || farbe > 0xFFFFFF)) {
			throw new Fehler("Die Farbe muss ein Wert zwischen 000000 und FFFFFF sein.");
		}
		MenuDao.setEmbed(menu.id(), t, b, farbe, p);
	}

	public static void menuStil(Menu menu, Menu.Style stil) {
		MenuDao.setStyle(menu.id(), stil);
	}

	/**
	 * Zieht ein Menü in einen anderen Kanal. Eine bereits gepostete Nachricht
	 * wird im alten Kanal gelöscht — zwei Menüs mit denselben Knöpfen wären
	 * für die Nutzer nicht zu unterscheiden.
	 */
	public static void menuKanal(Guild guild, Menu menu, String channelId) {
		final TextChannel neu = textkanal(guild, channelId);
		if (neu.getId().equals(menu.channelId())) {
			return;
		}
		nachrichtLoeschen(guild, menu);
		MenuDao.setChannel(menu.id(), neu.getId());
	}

	public static void menuLoeschen(Guild guild, Menu menu) {
		nachrichtLoeschen(guild, menu);
		MenuDao.delete(menu.id());
	}

	private static void nachrichtLoeschen(Guild guild, Menu menu) {
		if (!menu.isPosted()) {
			return;
		}
		final TextChannel alt = guild.getTextChannelById(menu.channelId());
		if (alt == null) {
			return;
		}
		try {
			alt.deleteMessageById(menu.messageId()).complete();
		} catch (final net.dv8tion.jda.api.exceptions.ErrorResponseException e) {
			if (e.getErrorResponse() != net.dv8tion.jda.api.requests.ErrorResponse.UNKNOWN_MESSAGE) {
				throw e;
			}
		}
	}

	/** Hängt ein Panel ins Menü oder ändert seinen Eintrag. */
	public static void menuEintrag(Menu menu, Panel panel, String label, String emoji, String beschreibung,
			Integer knopfstil, Integer position) {
		if (!panel.guildId().equals(menu.guildId())) {
			throw new Fehler("Panel und Menü gehören zu verschiedenen Servern.");
		}
		final List<MenuEntry> vorhanden = MenuDao.entries(menu.id());
		final Optional<MenuEntry> alt = vorhanden.stream().filter(e -> e.panelId() == panel.id()).findFirst();
		if (alt.isEmpty() && vorhanden.size() >= MAX_EINTRAEGE) {
			throw new Fehler("Ein Menü fasst höchstens " + MAX_EINTRAEGE + " Einträge.");
		}
		final String l = leerZuNull(label) == null ? panel.name() : label.trim();
		laenge(l, 80, "Die Beschriftung");
		final String d = leerZuNull(beschreibung);
		laenge(d, 100, "Die Beschreibung");
		final String em = leerZuNull(emoji);
		if (em != null && !emojiGueltig(em)) {
			throw new Fehler("„" + em + "“ ist kein Emoji. Erlaubt: ein Unicode-Emoji oder <:name:id>.");
		}
		final int stil = knopfstil == null ? alt.map(MenuEntry::buttonStyle).orElse(2) : knopfstil;
		if (stil < 1 || stil > 4) {
			throw new Fehler("Knopfstil muss 1 (blau), 2 (grau), 3 (grün) oder 4 (rot) sein.");
		}
		final int pos = position != null ? position
				: alt.map(MenuEntry::position).orElse(vorhanden.size());
		MenuDao.addEntry(menu.id(), panel.id(), l, d, em, stil, pos);
	}

	public static void menuEintragEntfernen(Menu menu, Panel panel) {
		MenuDao.removeEntry(menu.id(), panel.id());
	}

	/** Setzt die Reihenfolge der Einträge nach der Liste der Panel-IDs. */
	public static void menuReihenfolge(Menu menu, List<Long> panelIds) {
		final List<MenuEntry> vorhanden = MenuDao.entries(menu.id());
		int pos = 0;
		for (final Long pid : panelIds) {
			final Optional<MenuEntry> e = vorhanden.stream().filter(x -> x.panelId() == pid).findFirst();
			if (e.isEmpty()) {
				throw new Fehler("Panel " + pid + " steht nicht in Menü " + menu.id() + ".");
			}
			final MenuEntry m = e.get();
			MenuDao.addEntry(menu.id(), m.panelId(), m.label(), m.description(), m.emoji(), m.buttonStyle(), pos++);
		}
	}

	/**
	 * Postet das Menü oder gleicht die vorhandene Nachricht ab. Wartet auf
	 * Discord; nicht auf dem JDA-Ereignisthread aufrufen.
	 */
	public static String menuPosten(Guild guild, Menu menu) {
		final TextChannel kanal = guild.getTextChannelById(menu.channelId());
		if (kanal == null) {
			throw new Fehler("Der Kanal des Menüs existiert nicht mehr.");
		}
		if (MenuDao.entries(menu.id()).isEmpty()) {
			throw new Fehler("Das Menü hat noch kein Panel.");
		}
		if (leerZuNull(menu.embedTitle()) == null && leerZuNull(menu.embedText()) == null) {
			throw new Fehler("Das Menü hat noch weder Titel noch Text.");
		}
		try {
			return MenuRenderer.veroeffentlichen(kanal, menu);
		} catch (final net.dv8tion.jda.api.exceptions.ErrorResponseException e) {
			throw new Fehler("Discord hat das Menü abgelehnt: " + e.getMeaning());
		}
	}

	/**
	 * Gleicht alle schon geposteten Menüs eines Servers ab. Noch nie gepostete
	 * bleiben unsichtbar — dieselbe Regel wie beim Start.
	 *
	 * @return wie viele abgeglichen wurden
	 */
	public static int alleAbgleichen(Guild guild) {
		int n = 0;
		for (final Menu m : MenuDao.byGuild(guild.getId())) {
			if (m.isPosted() && !MenuDao.entries(m.id()).isEmpty()) {
				menuPosten(guild, m);
				n++;
			}
		}
		return n;
	}

	// -----------------------------------------------------------------------
	// Server
	// -----------------------------------------------------------------------

	public static void limitSetzen(String guildId, int wert) {
		if (wert < 0 || wert > 100) {
			throw new Fehler("Das Limit muss zwischen 0 und 100 liegen. 0 heißt unbegrenzt.");
		}
		GuildConfigDao.setGlobalLimit(guildId, wert);
	}

	public static void sperren(String guildId, String subjectId, boolean istRolle, String grund, String von) {
		final String id = idOderFehler(subjectId, "Person oder Rolle");
		laenge(leerZuNull(grund), 500, "Der Grund");
		Database.update(
				"INSERT INTO blacklist (guild_id, subject_id, is_role, reason, added_by) "
						+ "VALUES (?, ?, ?, ?, ?) ON CONFLICT (guild_id, subject_id) "
						+ "DO UPDATE SET reason = EXCLUDED.reason, added_by = EXCLUDED.added_by",
				guildId, id, istRolle, leerZuNull(grund), von);
	}

	public static void entsperren(String guildId, String subjectId) {
		Database.update("DELETE FROM blacklist WHERE guild_id = ? AND subject_id = ?", guildId,
				idOderFehler(subjectId, "Person oder Rolle"));
	}

	// -----------------------------------------------------------------------
	// Protokoll
	// -----------------------------------------------------------------------

	/**
	 * Schreibt eine Änderung in die Konsole; der Log-Spiegel trägt sie nach
	 * {@code 💻┋ticket-bot-log}. Namen statt Erwähnungen — der Spiegel pingt
	 * zwar nicht, aber lesbar soll es auch ohne Discord-Auflösung sein.
	 */
	public static void protokoll(Guild guild, String userId, String quelle, String was) {
		final Member m = guild == null || userId == null ? null : guild.getMemberById(userId);
		final String wer = (m != null ? m.getUser().getName() : "?") + " (" + userId + ")";
		System.out.println("⚙️ Ticket-Verwaltung [" + quelle + "] " + (guild == null ? "?" : guild.getName())
				+ " · " + wer + ": " + was);
	}

	// -----------------------------------------------------------------------
	// Hilfen
	// -----------------------------------------------------------------------

	static boolean parseBool(String raw) {
		return switch (raw.toLowerCase()) {
			case "ja", "true", "an", "1" -> true;
			case "nein", "false", "aus", "0" -> false;
			default -> throw new IllegalArgumentException(raw);
		};
	}

	/**
	 * Ein Unicode-Emoji oder ein eigenes Emoji in Discords Schreibweise.
	 *
	 * Discord lehnt die ganze Nachricht ab, wenn ein einziger Knopf ein
	 * ungültiges Emoji trägt — und dann steht das Menü gar nicht. Deshalb wird
	 * hier grob vorgeprüft: keine Buchstaben oder Ziffern, kurz.
	 */
	public static boolean emojiGueltig(String e) {
		if (e.matches("<a?:[A-Za-z0-9_]{2,32}:\\d{15,21}>")) {
			return true;
		}
		final int punkte = e.codePointCount(0, e.length());
		if (punkte < 1 || punkte > 10) {
			return false;
		}
		// Ziffern-Emojis wie 1️⃣ beginnen mit einer ASCII-Ziffer, gefolgt von FE0F/20E3.
		if (e.matches("[0-9#*]️?⃣")) {
			return true;
		}
		return e.codePoints().noneMatch(cp -> cp < 0x80);
	}

	private static String idOderFehler(String roh, String was) {
		final String r = roh == null ? "" : roh.trim().replaceAll("[<#@&!>]", "");
		if (!r.matches("\\d{15,21}")) {
			throw new Fehler(was + ": „" + roh + "“ ist keine Discord-ID.");
		}
		return r;
	}

	private static void zahlZwischen(Object wert, int min, int max, String was) {
		final int z = (Integer) wert;
		if (z < min || z > max) {
			throw new Fehler(was + " muss zwischen " + min + " und " + max + " liegen.");
		}
	}

	private static void laenge(Object wert, int max, String was) {
		if (wert instanceof String s && s.length() > max) {
			throw new Fehler(was + " ist zu lang (" + s.length() + " von höchstens " + max + " Zeichen).");
		}
	}

	private static String leerZuNull(String s) {
		return s == null || s.isBlank() ? null : s.trim();
	}
}
