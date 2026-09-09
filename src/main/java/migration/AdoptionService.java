package migration;

import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import db.Database;
import db.PanelDao;
import db.TicketDao;
import lostticketbot.Bot;
import model.Panel;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.PermissionOverride;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;

/**
 * Uebernimmt Ticketkanaele, die noch Ticket Tool angelegt hat.
 *
 * Nach dem Umschalten existieren die offenen Tickets weiter, aber der neue Bot
 * kennt sie nicht — und der Schliessen-Knopf darin gehoert Ticket Tool und wird
 * wirkungslos, sobald dessen Bot geht. Diese Klasse traegt die Kanaele nach und
 * macht sie damit wieder bedienbar.
 *
 * WICHTIG: Nur diese einmalige Uebernahme muss ueberhaupt etwas erkennen.
 * Danach haengt jedes Ticket an seiner channel_id, nicht am Namen — umbenennen,
 * verschieben und Zusaetze wie "-angenommen" sind ab dann folgenlos.
 *
 * Erkannt wird ein Ticket daran, dass TICKET TOOL DIE ERSTE NACHRICHT im Kanal
 * geschrieben hat. Das ist das einzige Merkmal, das ein Umbenennen ueberlebt:
 * "lost-3-besprechung" sieht fast wie ein Ticket aus und ist keines,
 * "ticket-wueste-lost-5" heisst wie eines und ist auch keines — und ein
 * umbenanntes Ticket waere ueber den Namen gar nicht mehr auffindbar.
 *
 * Der Name liefert nur noch die laufende Nummer. Drei Eigenheiten dabei:
 *
 * 1. Die Nummer steht nicht immer am Ende. Eure Leader haengen den Status an
 *    ("th18-0135-angenommen"), deshalb wird die erste Zahlengruppe nach dem
 *    Praefix genommen, nicht die letzte im Namen.
 * 2. Ist der Name gar nicht mehr lesbar, wird die Nummer als unbekannt
 *    vermerkt statt geraten — das Ticket bleibt trotzdem uebernehmbar.
 * 3. Es gibt Nummern oberhalb der Zaehlerstaende aus dem Log (lost-f2p-2-251,
 *    waehrend der Log bei 249 endet). Die Zaehler werden deshalb nachgezogen,
 *    sonst vergibt der Bot einen Namen zweimal.
 */
public final class AdoptionService {

	/** Ticket Tools Anwendungs-ID. */
	private static final String TICKET_TOOL_ID = "557628352828014614";

	private AdoptionService() {
	}

	/** Ein gefundener Kanal und das, was wir ueber ihn wissen. */
	public record Kandidat(TextChannel channel, Panel panel, int nummer, boolean geschlossen,
			String ownerId, String hinweis) {

		public boolean uebernehmbar() {
			return ownerId != null && panel != null;
		}
	}

	public record Ergebnis(List<Kandidat> uebernommen, List<Kandidat> uebersprungen,
			List<String> zaehlerAngepasst) {
	}

	// -----------------------------------------------------------------------
	// Suchen
	// -----------------------------------------------------------------------

	/**
	 * Sucht Ticketkanaele im ganzen Server.
	 *
	 * Zweistufig, um nicht fuer jeden der teils 500 Kanaele die Historie
	 * abzurufen: erst die Form der Berechtigungen pruefen — das kostet nichts,
	 * die stehen im Cache — und nur bei den verbliebenen die erste Nachricht
	 * lesen.
	 */
	public static List<Kandidat> finden(Guild guild, Consumer<String> fortschritt) {
		final List<Panel> panels = PanelDao.byGuild(guild.getId());
		final List<Kandidat> gefunden = new ArrayList<>();

		// Log- und Ablagekanaele ausnehmen. Dort hat Ticket Tool ebenfalls die
		// erste Nachricht geschrieben — naemlich einen Transcript-Eintrag — und
		// mit ihren Rechten sehen sie aus wie Tickets. Ohne diesen Ausschluss
		// steht der Orga-Log-Kanal in der Vorschau.
		final Set<String> ausgenommen = ausgenommeneKanaele(panels);

		final List<TextChannel> verdaechtig = guild.getTextChannels().stream()
				.filter(c -> !ausgenommen.contains(c.getId()))
				.filter(c -> TicketDao.byChannel(c.getId()).isEmpty())
				.filter(AdoptionService::siehtNachTicketAus)
				.toList();

		if (fortschritt != null) {
			fortschritt.accept(verdaechtig.size()
					+ " Kanäle mit Ticket-Berechtigungen, prüfe die Verläufe …");
		}

		for (final TextChannel channel : verdaechtig) {
			ersteNachricht(channel)
					.filter(m -> TICKET_TOOL_ID.equals(m.getAuthor().getId()))
					.ifPresent(willkommen -> gefunden.add(auswerten(channel, willkommen, panels)));
		}
		return gefunden;
	}

	/** Die Log-Kanaele aller Panels und der Storage-Kanal — nie Tickets. */
	private static Set<String> ausgenommeneKanaele(List<Panel> panels) {
		final Set<String> aus = new HashSet<>();
		for (final Panel p : panels) {
			if (p.logChannelId() != null && !p.logChannelId().isBlank()) {
				aus.add(p.logChannelId());
			}
		}
		final String storage = Bot.storageChannelId();
		if (storage != null && !storage.isBlank()) {
			aus.add(storage);
		}
		return aus;
	}

	/**
	 * Grobfilter ohne API-Aufruf: ein Ticket entzieht der Standardrolle die
	 * Sicht und gibt sie mindestens einer Einzelperson zurueck. Normale Kanaele
	 * arbeiten mit Rollen, nicht mit Einzelpersonen.
	 */
	private static boolean siehtNachTicketAus(TextChannel channel) {
		boolean everyoneAusgesperrt = false;
		boolean einzelpersonBerechtigt = false;

		for (final PermissionOverride o : channel.getPermissionOverrides()) {
			if (o.isRoleOverride() && o.getRole() != null
					&& o.getRole().getIdLong() == channel.getGuild().getPublicRole().getIdLong()
					&& o.getDenied().contains(Permission.VIEW_CHANNEL)) {
				everyoneAusgesperrt = true;
			}
			if (o.isMemberOverride() && o.getAllowed().contains(Permission.VIEW_CHANNEL)) {
				einzelpersonBerechtigt = true;
			}
		}
		return everyoneAusgesperrt && einzelpersonBerechtigt;
	}

	private static Optional<Message> ersteNachricht(TextChannel channel) {
		try {
			final List<Message> verlauf = channel.getHistoryFromBeginning(1)
					.complete().getRetrievedHistory();
			return verlauf.isEmpty() ? Optional.empty() : Optional.of(verlauf.get(0));
		} catch (final RuntimeException e) {
			System.err.println("Verlauf von " + channel.getName() + " nicht lesbar: " + e.getMessage());
			return Optional.empty();
		}
	}

	/** Ergebnis der Zuordnung eines Kanalnamens zu einem Panel. */
	record Treffer(Panel panel, int nummer, boolean geschlossen, String zusatz) {
	}

	/**
	 * Ordnet einen Kanalnamen dem Panel mit dem LAENGSTEN passenden Praefix zu.
	 *
	 * Der laengste Treffer ist nicht Feinschliff, sondern noetig:
	 * "lost-f2p-2-250" passt sowohl auf "lost-f2p-" als auch auf
	 * "lost-f2p-2-". Der kuerzere wuerde die Nummer als 2 lesen statt als 250
	 * und das Ticket dem falschen Clan zuordnen. Genauso passt
	 * "lost-cr-closed-1318" auch auf "lost-cr-".
	 *
	 * Als reine Funktion gehalten, damit sie ohne Discord testbar bleibt.
	 */
	static Optional<Treffer> zuordnen(List<Panel> panels, String name) {
		Treffer bester = null;
		int besteLaenge = -1;

		for (final Panel panel : panels) {
			for (final boolean geschlossen : new boolean[] { true, false }) {
				final String praefix = praefix(geschlossen
						? panel.namePatternClosed()
						: panel.namePatternOpen());
				if (praefix.isBlank() || !name.startsWith(praefix)) {
					continue;
				}
				final OptionalNumber nummer = ersteZahlNach(name, praefix);
				if (nummer.fehlt() || praefix.length() <= besteLaenge) {
					continue;
				}
				bester = new Treffer(panel, nummer.wert(), geschlossen,
						name.substring(praefix.length() + nummer.laenge()));
				besteLaenge = praefix.length();
			}
		}
		return Optional.ofNullable(bester);
	}

	/**
	 * Liest aus Ticket Tools Willkommensnachricht, wem das Ticket gehoert und um
	 * welchen Typ es geht.
	 *
	 * Beides steht in der Nachricht, nicht im Kanalnamen — der Eroeffner als
	 * erste Erwaehnung, der Ticket-Typ als Embed-Beschreibung ("Rathaus 17").
	 * Damit ueberlebt die Zuordnung ein Umbenennen des Kanals.
	 */
	private static Kandidat auswerten(TextChannel channel, Message willkommen, List<Panel> panels) {
		final String ownerId = willkommen.getMentions().getUsers().isEmpty()
				? null
				: willkommen.getMentions().getUsers().get(0).getId();

		final String typ = willkommen.getEmbeds().stream()
				.findFirst()
				.map(MessageEmbed::getDescription)
				.orElse(null);

		final Optional<Panel> ausTyp = panelAusTyp(panels, typ);
		final Optional<Treffer> ausName = zuordnen(panels, channel.getName());

		final Panel panel = ausTyp.orElseGet(() -> ausName.map(Treffer::panel).orElse(null));
		final int nummer = ausName.map(Treffer::nummer).orElse(0);
		final boolean geschlossen = ausName.map(Treffer::geschlossen).orElse(false);

		final List<String> hinweise = new ArrayList<>();
		if (ausName.isPresent()) {
			if (!ausName.get().zusatz().isBlank()) {
				hinweise.add("Namenszusatz " + ausName.get().zusatz());
			}
			if (ausTyp.isPresent() && ausTyp.get().id() != ausName.get().panel().id()) {
				hinweise.add("Name deutet auf " + ausName.get().panel().name()
						+ ", Willkommensnachricht auf " + ausTyp.get().name() + " — letzterer gefolgt");
			}
		} else {
			hinweise.add("Nummer aus dem Namen nicht lesbar - beim Übernehmen wird die nächste vergeben");
		}
		if (ownerId == null) {
			hinweise.add("Eröffner nicht erkennbar");
		}
		if (panel == null) {
			hinweise.add("Kein passendes Panel" + (typ == null ? "" : " für " + typ));
		}

		return new Kandidat(channel, panel, nummer, geschlossen, ownerId,
				hinweise.isEmpty() ? null : String.join("; ", hinweise));
	}

	/** Sucht das Panel, dessen Titel, Embed-Text oder Name dem Ticket-Typ entspricht. */
	static Optional<Panel> panelAusTyp(List<Panel> panels, String typ) {
		if (typ == null || typ.isBlank()) {
			return Optional.empty();
		}
		final String gesucht = typ.trim().toLowerCase(Locale.ROOT);
		for (final Panel p : panels) {
			if (gleich(p.welcomeEmbedTitle(), gesucht) || gleich(p.welcomeEmbedText(), gesucht)
					|| gleich(p.name(), gesucht)) {
				return Optional.of(p);
			}
		}
		return Optional.empty();
	}

	private static boolean gleich(String wert, String gesucht) {
		return wert != null && wert.trim().toLowerCase(Locale.ROOT).equals(gesucht);
	}


	// -----------------------------------------------------------------------
	// Uebernehmen
	// -----------------------------------------------------------------------

	public static Ergebnis uebernehmen(Guild guild, List<Kandidat> kandidaten) {
		final List<Kandidat> ok = new ArrayList<>();
		final List<Kandidat> uebersprungen = new ArrayList<>();

		for (final Kandidat k : kandidaten) {
			if (!k.uebernehmbar()) {
				uebersprungen.add(k);
				continue;
			}
			try {
				// Nummer 0 heisst: aus dem Namen war keine zu lesen. Erst hier
				// eine vergeben, nicht schon in der Vorschau — die soll keine
				// Nummern verbrauchen, auch wenn man sie zehnmal aufruft.
				final int nummer = k.nummer() > 0
						? k.nummer()
						: PanelDao.nextNumber(k.panel().id());

				final long id = TicketDao.create(guild.getId(), k.panel().id(), nummer,
						k.channel().getId(), k.channel().getName(), k.ownerId());
				if (k.geschlossen()) {
					// Bewusst ohne Schliesser und ohne Grund: wer es damals
					// geschlossen hat, steht nur im Ticket-Tool-Log.
					TicketDao.close(id, null, "aus Ticket Tool uebernommen", k.channel().getName());
				}
				ok.add(k);
			} catch (final RuntimeException e) {
				System.err.println("Uebernahme von " + k.channel().getName() + " fehlgeschlagen: " + e);
				uebersprungen.add(k);
			}
		}

		return new Ergebnis(ok, uebersprungen, zaehlerNachziehen(guild));
	}

	/**
	 * Setzt jeden Zaehler auf mindestens die hoechste uebernommene Nummer.
	 *
	 * Ohne diesen Schritt vergibt der Bot Namen erneut, die es schon gibt: der
	 * Log endet bei lost-f2p-2-249, offen ist aber lost-f2p-2-251.
	 */
	private static List<String> zaehlerNachziehen(Guild guild) {
		final List<String> angepasst = new ArrayList<>();
		for (final Panel panel : PanelDao.byGuild(guild.getId())) {
			final int hoechste = Database.count(
					"SELECT COALESCE(MAX(number), 0) FROM tickets WHERE panel_id = ?", panel.id());
			if (hoechste > panel.counter()) {
				PanelDao.set(panel.id(), "counter", hoechste);
				angepasst.add(panel.name() + ": " + panel.counter() + " → " + hoechste);
			}
		}
		return angepasst;
	}

	// -----------------------------------------------------------------------
	// Hilfsmittel
	// -----------------------------------------------------------------------

	/** "th18-{count}" -> "th18-" */
	static String praefix(String muster) {
		if (muster == null) {
			return "";
		}
		final int pos = muster.indexOf("{count}");
		final String roh = pos < 0 ? muster : muster.substring(0, pos);
		return Panel.sanitizeChannelName(roh + "x").replaceAll("x$", "");
	}

	record OptionalNumber(int wert, int laenge) {
		boolean fehlt() {
			return laenge == 0;
		}
	}

	/**
	 * Erste Zahlengruppe direkt hinter dem Praefix.
	 *
	 * Bewusst nicht die letzte Zahl im Namen: "th18-0135-angenommen" hat keine
	 * Zahl am Ende, und "lost-5-0088" haette sonst zwei Kandidaten.
	 */
	static OptionalNumber ersteZahlNach(String name, String praefix) {
		if (!name.startsWith(praefix) || name.length() <= praefix.length()) {
			return new OptionalNumber(0, 0);
		}
		final Matcher m = Pattern.compile("^(\\d+)").matcher(name.substring(praefix.length()));
		if (!m.find()) {
			return new OptionalNumber(0, 0);
		}
		try {
			return new OptionalNumber(Integer.parseInt(m.group(1)), m.group(1).length());
		} catch (final NumberFormatException e) {
			return new OptionalNumber(0, 0);
		}
	}

	static Optional<Panel> panelFuer(Guild guild, String kanalname) {
		for (final Panel p : PanelDao.byGuild(guild.getId())) {
			if (kanalname.startsWith(praefix(p.namePatternOpen()))) {
				return Optional.of(p);
			}
		}
		return Optional.empty();
	}
}
