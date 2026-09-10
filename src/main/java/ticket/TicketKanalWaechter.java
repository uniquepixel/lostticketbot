package ticket;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.annotation.Nonnull;

import db.PanelDao;
import db.TicketDao;
import model.Panel;
import model.Ticket;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.audit.ActionType;
import net.dv8tion.jda.api.audit.AuditLogEntry;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.channel.ChannelDeleteEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import transcript.TranscriptArchiver;

/**
 * Merkt, wenn ein Ticketkanal in Discord von Hand geloescht wird.
 *
 * Der wiederkehrende Fall aus dem Betrieb: jemand raeumt auf und entfernt den
 * Kanal ueber Discord statt ueber den Loeschknopf. Der Bot bekam davon bisher
 * nichts mit — in der Datenbank stand das Ticket weiter als "offen", der
 * Lagebericht zeigte wartende Bewerber, die es nicht mehr gab, und der Verlauf
 * lag nur noch in der Datenbank, also ausgerechnet in dem Teil, den dieses
 * System ausdruecklich als wegwerfbar behandelt. Viermal von Hand berichtigt.
 *
 * Der Waechter macht daraus denselben Ablauf wie beim Loeschknopf, nur
 * rueckwaerts: der Kanal ist schon weg, also wird gerettet was in der
 * Datenbank steht — Archiv-Post in den Storage-Kanal, Log-Eintrag am Panel,
 * Status auf {@code deleted}.
 *
 * Zwei Quellen, weil eine nicht reicht:
 * <ul>
 * <li>das {@link ChannelDeleteEvent}, solange der Bot laeuft,</li>
 * <li>ein Abgleich beim Start, denn wer waehrend eines Neustarts loescht,
 * loest kein Event aus, das noch jemand hoert.</li>
 * </ul>
 */
public class TicketKanalWaechter extends ListenerAdapter {

	static final String GRUND = "Kanal in Discord gelöscht";

	/**
	 * Mehr verwaiste Tickets als das beim Start, und der Waechter fasst nichts
	 * an.
	 *
	 * Denn dann ist die wahrscheinlichere Erklaerung nicht, dass jemand zwanzig
	 * Kanaele geloescht hat, sondern dass der Kanalcache unvollstaendig ist.
	 * Aus dieser Annahme heraus reihenweise Tickets auf "deleted" zu setzen
	 * waere schlimmer als der Zustand, den es zu beheben gilt — die Meldung
	 * bleibt, das Aufraeumen macht dann ein Mensch.
	 */
	static final int HOECHSTENS_BEIM_START = 5;

	/**
	 * Ein einziger Thread.
	 *
	 * Loeschungen sind selten, kommen aber gebuendelt: wer aufraeumt, entfernt
	 * mehrere Kanaele hintereinander. Nacheinander abzuarbeiten haelt die
	 * Archiv-Posts in Reihenfolge und laeuft nicht in Discords Rate-Limit. Und
	 * der Gateway-Thread darf hier ohnehin nicht warten — archivieren heisst
	 * hochladen.
	 */
	private static final ExecutorService ARBEIT = Executors.newSingleThreadExecutor(r -> {
		final Thread t = new Thread(r, "kanal-geloescht");
		t.setDaemon(true);
		return t;
	});

	/** Wer den Kanal entfernt hat — aus dem Audit-Log, sofern es das hergibt. */
	private record Loescher(String id, String name) {
	}

	// -----------------------------------------------------------------------
	// Im Betrieb
	// -----------------------------------------------------------------------

	@Override
	public void onChannelDelete(@Nonnull ChannelDeleteEvent event) {
		if (!event.isFromGuild()) {
			return;
		}
		final Guild guild = event.getGuild();
		final String kanalId = event.getChannel().getId();

		// Auch der Blick in die Datenbank gehoert nicht in den Gateway-Thread:
		// hier laufen alle Events aller Server durch.
		ARBEIT.execute(() -> {
			final Optional<Ticket> ticket = TicketDao.byChannel(kanalId);
			if (ticket.isEmpty()) {
				return;   // irgendein anderer Kanal, davon gibt es viele
			}
			if (TicketService.warEigeneLoeschung(kanalId)) {
				// Der Bot hat gerade selbst geloescht. Archiv, Log-Eintrag und
				// Status macht TicketService.delete; hier waere alles doppelt.
				return;
			}
			rette(guild, ticket.get(), werHatGeloescht(guild, kanalId, 3));
		});
	}

	// -----------------------------------------------------------------------
	// Beim Start
	// -----------------------------------------------------------------------

	/**
	 * Holt nach, was waehrend eines Neustarts passiert ist.
	 *
	 * Ein Deploy dauert ein paar Sekunden, und genau in dieser Luecke geloescht
	 * zu bekommen ist kein exotischer Fall — sondern der, den man ohne diesen
	 * Abgleich nie bemerkt, weil das Event ins Leere lief.
	 */
	@Override
	public void onReady(@Nonnull ReadyEvent event) {
		ARBEIT.execute(() -> {
			for (final Guild guild : event.getJDA().getGuilds()) {
				nachholen(guild);
			}
		});
	}

	/**
	 * Gilt je Server, nicht fuer alle auf einmal — sonst koennte ein Test den
	 * Abgleich nicht ueber einen einzelnen Server laufen lassen, ohne dabei die
	 * Produktivserver mitzunehmen.
	 */
	static void nachholen(Guild guild) {
		final List<Ticket> verwaist = new ArrayList<>();
		for (final Ticket ticket : TicketDao.nichtGeloescht(guild.getId())) {
			if (guild.getGuildChannelById(ticket.channelId()) == null) {
				verwaist.add(ticket);
			}
		}
		if (verwaist.isEmpty()) {
			return;
		}
		if (verwaist.size() > HOECHSTENS_BEIM_START) {
			System.err.println("Auf " + guild.getName() + " fehlen " + verwaist.size()
					+ " Ticketkanäle. Das sind zu viele für einen Zufall — der Bot fasst sie"
					+ " nicht an. Bitte mit ./check.sh nachsehen.");
			return;
		}
		for (final Ticket ticket : verwaist) {
			System.out.println("Beim Start bemerkt: Kanal zu Ticket " + ticket.channelName()
					+ " (id " + ticket.id() + ") gibt es nicht mehr.");
			rette(guild, ticket, werHatGeloescht(guild, ticket.channelId(), 1));
		}
	}

	// -----------------------------------------------------------------------

	/**
	 * Sichert den Verlauf eines Tickets, dessen Kanal es nicht mehr gibt.
	 *
	 * Reihenfolge wie in {@link TicketService#delete}: erst archivieren, dann
	 * den Status umstellen. Bricht das Archivieren ab, bleibt das Ticket in der
	 * Datenbank stehen und faellt beim naechsten Start wieder auf — verloren
	 * ist der Verlauf erst, wenn beides zusammen schiefgeht.
	 */
	private static void rette(Guild guild, Ticket ticket, Loescher von) {
		if (ticket.status() == Ticket.Status.DELETED) {
			return;
		}
		final String vonId = von == null ? null : von.id();

		try {
			// Ein offenes Ticket wird nur noch in der Datenbank geschlossen.
			// Umbenennen und verschieben faellt aus, es gibt ja nichts mehr,
			// was man umbenennen koennte.
			if (ticket.isOpen()) {
				TicketDao.close(ticket.id(), vonId, GRUND, ticket.channelName());
			}

			final Panel panel = ticket.panelId() == null
					? null
					: PanelDao.byId(ticket.panelId()).orElse(null);

			TicketDao.byId(ticket.id()).ifPresent(aktuell -> TranscriptArchiver
					.archiviereNachKanalLoeschung(guild, aktuell, panel, vonId));

			TicketDao.markDeleted(ticket.id());

			System.out.println("Ticketkanal " + ticket.channelName() + " (id " + ticket.id()
					+ ") wurde in Discord gelöscht"
					+ (von == null ? "" : " von " + von.name())
					+ " — Verlauf gesichert, Status steht auf 'deleted'.");
		} catch (final RuntimeException e) {
			System.err.println("Ticket " + ticket.channelName() + " (id " + ticket.id()
					+ "): Kanal ist weg, der Verlauf konnte aber nicht gesichert werden: " + e);
		}
	}

	/**
	 * Fragt das Audit-Log, wer den Kanal entfernt hat.
	 *
	 * Nur fuer den Nachweis im Log-Eintrag — scheitert es, wird trotzdem
	 * gerettet. Der Eintrag steht nicht immer sofort im Audit-Log, deshalb im
	 * Betrieb mehrere Anlaeufe; beim Abgleich nach dem Start ist die Loeschung
	 * lange her, da lohnt kein Warten.
	 */
	private static Loescher werHatGeloescht(Guild guild, String kanalId, int versuche) {
		if (!guild.getSelfMember().hasPermission(Permission.VIEW_AUDIT_LOGS)) {
			return null;
		}
		for (int versuch = 0; versuch < versuche; versuch++) {
			try {
				for (final AuditLogEntry eintrag : guild.retrieveAuditLogs()
						.type(ActionType.CHANNEL_DELETE).limit(25).complete()) {
					if (kanalId.equals(eintrag.getTargetId())) {
						final User nutzer = eintrag.getUser();
						return new Loescher(eintrag.getUserId(),
								nutzer == null ? eintrag.getUserId() : nutzer.getName());
					}
				}
			} catch (final RuntimeException e) {
				System.err.println("Audit-Log von " + guild.getName() + " nicht lesbar: "
						+ e.getMessage());
				return null;
			}
			if (versuch + 1 < versuche) {
				kurzWarten();
			}
		}
		return null;
	}

	private static void kurzWarten() {
		try {
			Thread.sleep(700);
		} catch (final InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
