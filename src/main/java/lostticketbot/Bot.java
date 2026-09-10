package lostticketbot;

import java.util.Optional;

import javax.annotation.Nonnull;

import db.Database;
import db.GuildConfigDao;
import api.TicketApiServer;
import commands.AdoptCommand;
import commands.CommandRegistry;
import commands.ConfigCommand;
import commands.LegacyCommand;
import commands.MenuCommand;
import commands.PanelCommand;
import commands.TicketCommand;
import commands.TranscriptCommand;
import db.MenuDao;
import db.PanelDao;
import db.TicketDao;
import model.Menu;
import model.Panel;
import model.Ticket;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRemoveEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.ChunkingFilter;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import net.dv8tion.jda.api.utils.messages.MessageRequest;
import panel.MenuRenderer;
import ticket.TicketInteractions;
import ticket.TicketKanalWaechter;
import ticket.TicketService;
import transcript.TranscriptArchiver;
import transcript.TranscriptRecorder;
import util.DiscordLog;

/**
 * Einstiegspunkt des LOST Ticket-Bots.
 *
 * Ein Prozess bedient beide Server. Alles Serverabhaengige steht in der
 * Datenbank und haengt an der guild_id — es gibt bewusst keine
 * GUILD_ID-Umgebungsvariable wie bei den aelteren LOST-Bots.
 */
public class Bot extends ListenerAdapter {

	public static final String VERSION = "0.1.0";

	private static JDA jda;
	private static String storageGuildId;
	private static String storageChannelId;
	private static TicketApiServer apiServer;

	public static void main(String[] args) {
		// Ganz zuerst: sonst fehlen dem Kanal genau die Startmeldungen, wegen
		// derer man nach einem Neustart hineinschaut. Bis JDA bereit ist,
		// puffert der Spiegel.
		DiscordLog.setup(System.getenv("TICKETBOT_LOG_CHANNEL_ID"));

		final String token = required("TICKETBOT_TOKEN");
		final String dbUrl = required("TICKETBOT_DB_URL");
		final String dbUser = required("TICKETBOT_DB_USER");
		final String dbPassword = System.getenv().getOrDefault("TICKETBOT_DB_PASSWORD", "");

		storageGuildId = System.getenv("TICKETBOT_STORAGE_GUILD_ID");
		storageChannelId = System.getenv("TICKETBOT_STORAGE_CHANNEL_ID");

		Database.init(dbUrl, dbUser, dbPassword);
		Database.applySchema();

		// Dashboard-API. Ohne gesetzten Port bleibt sie aus - der Bot laeuft
		// vollstaendig ohne sie, sie ist reine Zulieferung fuer die Website.
		final String apiPort = System.getenv("TICKETBOT_API_PORT");
		if (apiPort != null && !apiPort.isBlank()) {
			try {
				apiServer = new TicketApiServer(Integer.parseInt(apiPort),
						System.getenv("TICKETBOT_API_TOKEN"));
				apiServer.start();
			} catch (final Exception e) {
				System.err.println("Dashboard-API konnte nicht starten: " + e.getMessage());
			}
		}

		// Standardmaessig pingt nichts, was der Bot schreibt.
		//
		// Anlass ist der Storage-Kanal: dort steht in jedem Archiv-Post die
		// Owner-ID im Klartext, damit der Kanal ohne Datenbank durchsuchbar
		// bleibt — und benachrichtigte damit bei jedem geschlossenen Ticket den
		// Eroeffner. Erwaehnungen bleiben sichtbar und anklickbar, sie loesen nur
		// keine Benachrichtigung mehr aus.
		//
		// Wo ein Ping gewollt ist, steht er ausdruecklich da: siehe
		// TicketInteractions.postWelcome. Damit ist Anpingen die Ausnahme, die man
		// begruenden muss, statt der Nebenwirkung, die man uebersieht.
		erwaehnungenStandardmaessigStumm();

		jda = JDABuilder.createDefault(token)
				// GUILD_MEMBERS: noetig, um mitzubekommen, wenn ein Bewerber den
				// Server verlaesst (Ticket Tool nennt das "Missing user check").
				// MESSAGE_CONTENT: ohne diesen Intent bleiben alle Transcripts leer.
				// Beide sind privilegiert und muessen im Developer Portal
				// eingeschaltet sein — unter 100 Servern ohne Verifizierung.
				.enableIntents(GatewayIntent.GUILD_MEMBERS, GatewayIntent.MESSAGE_CONTENT)
				.setMemberCachePolicy(MemberCachePolicy.ALL)
				.setChunkingFilter(ChunkingFilter.ALL)
				.setActivity(Activity.listening("eure Anliegen"))
				// TicketKanalWaechter hoert auf ChannelDeleteEvent: wird ein
				// Ticketkanal von Hand in Discord geloescht, rettet er den
				// Verlauf ins Archiv und stellt den Status um. Ohne ihn stand
				// das Ticket weiter als offen in der Datenbank.
				.addEventListeners(new Bot(), new TicketInteractions(), new TranscriptRecorder(),
						new TicketKanalWaechter(),
						new PanelCommand(), new MenuCommand(), new LegacyCommand(), new TicketCommand(), new ConfigCommand(), new AdoptCommand(), new TranscriptCommand())
				.build();

		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			if (apiServer != null) {
				apiServer.stop();
			}
			Database.shutdown();
		}, "shutdown"));
	}

	/** Siehe den Kommentar am Aufruf. Eigene Methode, damit ein Test sie festhalten kann. */
	static void erwaehnungenStandardmaessigStumm() {
		MessageRequest.setDefaultMentions(java.util.Collections.emptyList());
	}

	private static String required(String name) {
		final String value = System.getenv(name);
		if (value == null || value.isBlank()) {
			throw new IllegalStateException("Umgebungsvariable " + name + " fehlt.");
		}
		return value;
	}

	@Override
	public void onReady(@Nonnull ReadyEvent event) {
		// Erst hier, nicht direkt nach build(): build() kehrt sofort zurueck,
		// der Kanalcache fuellt sich aber erst mit READY. Wer vorher sendet,
		// findet seinen Log-Kanal nicht.
		DiscordLog.setJda(event.getJDA());

		System.out.println("LOST Ticket-Bot " + VERSION + " ist bereit.");
		for (final Guild guild : event.getJDA().getGuilds()) {
			GuildConfigDao.ensure(guild.getId());
			System.out.println("  Server: " + guild.getName() + " (" + guild.getId() + "), "
					+ TicketDao.allOpen(guild.getId()).size() + " offene Tickets");
			CommandRegistry.register(guild);
		}
		refreshMenus(event.getJDA());

		if (storageChannelId == null || storageChannelId.isBlank()) {
			System.out.println("  Hinweis: kein Storage-Kanal gesetzt — Transcripts werden noch nicht archiviert.");
		}
	}

	/**
	 * Bringt jede BEREITS GEPOSTETE Menuenachricht auf den Stand der
	 * Konfiguration.
	 *
	 * Damit wirkt eine Aenderung an Panels oder Beschriftungen nach einem
	 * Neustart von selbst. Menues, die es noch nie in einen Kanal geschafft
	 * haben, bleiben unangetastet - veroeffentlicht wird ausschliesslich per
	 * /menu posten. Ohne diese Trennung postet ein Neustart jede vorbereitete
	 * Konfiguration sofort auf den Server.
	 */
	private void refreshMenus(JDA jda) {
		for (final Guild guild : jda.getGuilds()) {
			for (final Menu menu : MenuDao.byGuild(guild.getId())) {
				final TextChannel channel = guild.getTextChannelById(menu.channelId());
				if (channel == null) {
					System.err.println("Menue " + menu.id() + ": Kanal " + menu.channelId() + " gibt es nicht mehr.");
					continue;
				}
				MenuRenderer.nurAktualisieren(channel, menu);
			}
		}
	}

	/**
	 * Verlaesst der Eroeffner den Server, wird sein Ticket geschlossen, sofern
	 * das Panel das vorsieht. Im Bestand ist das uneinheitlich gesetzt (beim
	 * F2P-Panel an, bei CR-lost aus); hier entscheidet allein die Konfiguration.
	 */
	@Override
	public void onGuildMemberRemove(@Nonnull GuildMemberRemoveEvent event) {
		final Guild guild = event.getGuild();
		final String userId = event.getUser().getId();

		new Thread(() -> {
			for (final Ticket ticket : TicketDao.openByOwner(guild.getId(), userId)) {
				if (ticket.panelId() == null) {
					continue;
				}
				final Optional<Panel> maybePanel = PanelDao.byId(ticket.panelId());
				if (maybePanel.isEmpty() || !maybePanel.get().closeOnOwnerLeave()) {
					continue;
				}
				final Panel panel = maybePanel.get();

				try {
					if (panel.ownerLeaveMessage() != null && !panel.ownerLeaveMessage().isBlank()) {
						final TextChannel channel = guild.getTextChannelById(ticket.channelId());
						if (channel != null) {
							channel.sendMessage(panel.ownerLeaveMessage()).complete();
						}
					}
					TicketService.close(guild, ticket, panel, null, "Eroeffner hat den Server verlassen");
					TicketDao.byId(ticket.id()).ifPresent(closed -> {
						TranscriptArchiver.archive(guild, closed, panel, null);
						// Auch hier die Schliessnachricht: gerade bei einem
						// weggegangenen Bewerber will das Team danach oft
						// aufraeumen, und der Loeschknopf ist dann zur Hand.
						final TextChannel kanal = guild.getTextChannelById(closed.channelId());
						if (kanal != null) {
							TicketInteractions.postCloseNotice(kanal, closed, null);
						}
					});
					System.out.println("Ticket " + ticket.channelName() + " geschlossen: Eroeffner ist weg.");
				} catch (final RuntimeException e) {
					System.err.println("Automatisches Schliessen von " + ticket.channelName()
							+ " fehlgeschlagen: " + e);
				}
			}
		}, "owner-left-" + userId).start();
	}

	public static JDA jda() {
		return jda;
	}

	public static String storageGuildId() {
		return storageGuildId;
	}

	public static String storageChannelId() {
		return storageChannelId;
	}
}
