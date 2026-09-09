package lostticketbot;

import java.util.Optional;

import javax.annotation.Nonnull;

import db.Database;
import db.GuildConfigDao;
import db.PanelDao;
import db.TicketDao;
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
import ticket.TicketInteractions;
import ticket.TicketService;
import transcript.TranscriptRecorder;

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

	public static void main(String[] args) {
		final String token = required("TICKETBOT_TOKEN");
		final String dbUrl = required("TICKETBOT_DB_URL");
		final String dbUser = required("TICKETBOT_DB_USER");
		final String dbPassword = System.getenv().getOrDefault("TICKETBOT_DB_PASSWORD", "");

		storageGuildId = System.getenv("TICKETBOT_STORAGE_GUILD_ID");
		storageChannelId = System.getenv("TICKETBOT_STORAGE_CHANNEL_ID");

		Database.init(dbUrl, dbUser, dbPassword);
		Database.applySchema();

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
				.addEventListeners(new Bot(), new TicketInteractions(), new TranscriptRecorder())
				.build();

		Runtime.getRuntime().addShutdownHook(new Thread(Database::shutdown, "db-shutdown"));
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
		System.out.println("LOST Ticket-Bot " + VERSION + " ist bereit.");
		for (final Guild guild : event.getJDA().getGuilds()) {
			GuildConfigDao.ensure(guild.getId());
			System.out.println("  Server: " + guild.getName() + " (" + guild.getId() + "), "
					+ TicketDao.allOpen(guild.getId()).size() + " offene Tickets");
		}
		if (storageChannelId == null || storageChannelId.isBlank()) {
			System.out.println("  Hinweis: kein Storage-Kanal gesetzt — Transcripts werden noch nicht archiviert.");
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
