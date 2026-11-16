package spielerkarte;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import javax.annotation.Nonnull;

import db.GuildConfigDao;
import db.TicketDao;
import model.Ticket;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

/**
 * Postet die Spielerkarte, sobald jemand in einem Ticket einen Spielertag nennt.
 *
 * <b>Standardmäßig aus.</b> Eingeschaltet wird je Server über
 * {@code TICKETBOT_SPIELERKARTE_GUILDS} (Komma-Liste von Guild-IDs) in
 * {@code bot.env}. Je Ticket und Tag höchstens eine Karte, je Ticket höchstens
 * drei — sonst postet ein Bewerber mit fünf Accounts oder ein Vize, der Tags
 * vergleicht, den Kanal voll. Der Merker liegt im Speicher; nach einem Neustart
 * kann ein schon gezeigter Tag also noch einmal kommen, das ist hinnehmbar.
 */
public class SpielerkartenListener extends ListenerAdapter {

	static final int MAX_JE_TICKET = 3;

	private static final ExecutorService ARBEIT = Executors.newSingleThreadExecutor(r -> {
		final Thread t = new Thread(r, "spielerkarte");
		t.setDaemon(true);
		return t;
	});

	private final Set<String> guilds;
	private final Set<String> gezeigt = ConcurrentHashMap.newKeySet();
	private final ConcurrentHashMap<Long, Integer> jeTicket = new ConcurrentHashMap<>();

	public SpielerkartenListener() {
		this(System.getenv("TICKETBOT_SPIELERKARTE_GUILDS"));
	}

	SpielerkartenListener(String guildListe) {
		this.guilds = guildListe == null ? Set.of()
				: Arrays.stream(guildListe.split(",")).map(String::trim).filter(s -> !s.isEmpty())
						.collect(Collectors.toUnmodifiableSet());
	}

	public boolean an() {
		return !guilds.isEmpty();
	}

	@Override
	public void onMessageReceived(@Nonnull MessageReceivedEvent event) {
		if (!an() || !event.isFromGuild() || event.getAuthor().isBot()
				|| !guilds.contains(event.getGuild().getId())) {
			return;
		}
		final Optional<String> tag = Spielertag.finde(event.getMessage().getContentRaw());
		if (tag.isEmpty()) {
			return;
		}
		final Optional<Ticket> ticket = TicketDao.byChannel(event.getChannel().getId());
		if (ticket.isEmpty()) {
			return;
		}
		final long ticketId = ticket.get().id();
		if (!vormerken(ticketId, tag.get())) {
			return;
		}
		final String guildId = event.getGuild().getId();
		ARBEIT.execute(() -> {
			final CocSpieler.Antwort a = CocSpieler.hole(tag.get());
			if (!a.ok()) {
				// Bewusst ohne die Wörter, auf die die Wache ruft: eine tote CoC-API
				// meldet schon der LOST Manager, und ein vertippter Tag ist kein Fehler.
				System.out.println("Spielerkarte für #" + tag.get() + " (Ticket " + ticketId
						+ ") übersprungen: " + a.grund());
				return;
			}
			event.getChannel().sendMessageEmbeds(
					Spielerkarte.bauen(a.profil(), GuildConfigDao.get(guildId).ticketEmbedColor())).queue(
							ok -> System.out.println("Spielerkarte für #" + tag.get() + " in Ticket " + ticketId + " gepostet."),
							fehler -> System.err.println("Spielerkarte für #" + tag.get() + " ließ sich nicht posten: " + fehler));
		});
	}

	/** true, wenn für dieses Ticket und diesen Tag noch keine Karte kam und Platz ist. */
	boolean vormerken(long ticketId, String tag) {
		if (!gezeigt.add(ticketId + "|" + tag)) {
			return false;
		}
		final int n = jeTicket.merge(ticketId, 1, Integer::sum);
		if (n > MAX_JE_TICKET) {
			return false;
		}
		return true;
	}
}
