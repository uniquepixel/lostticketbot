package ticket;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.annotation.Nonnull;

import db.GuildConfigDao;
import db.PanelDao;
import db.TicketDao;
import model.GuildConfig;
import model.Panel;
import model.Ticket;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import panel.MenuRenderer;
import transcript.TranscriptArchiver;
import util.Dm;
import util.MessageUtil;

/**
 * Verarbeitet Klicks auf Menues und auf die Knoepfe im Ticket.
 *
 * Alles, was Discord-Aufrufe blockierend absetzt, laeuft in einem eigenen
 * Thread — die Interaktion wird vorher mit {@code deferReply} quittiert, weil
 * Discord sonst nach drei Sekunden abbricht. Kanaele anzulegen dauert
 * regelmaessig laenger.
 */
public class TicketInteractions extends ListenerAdapter {

	public static final String CLOSE_PREFIX = "tb:close:";
	public static final String CLOSE_CONFIRM_PREFIX = "tb:close-confirm:";
	public static final String CLOSE_ABORT = "tb:close-abort";
	public static final String DELETE_CONFIRM_PREFIX = "tb:delete-confirm:";

	@Override
	public void onButtonInteraction(@Nonnull ButtonInteractionEvent event) {
		final String id = event.getComponentId();

		if (id.startsWith(MenuRenderer.OPEN_PREFIX)) {
			handleOpen(event, parseId(id, MenuRenderer.OPEN_PREFIX));
		} else if (id.startsWith(DELETE_CONFIRM_PREFIX)) {
			handleDeleteConfirmed(event, parseId(id, DELETE_CONFIRM_PREFIX));
		} else if (id.startsWith(CLOSE_CONFIRM_PREFIX)) {
			handleCloseConfirmed(event, parseId(id, CLOSE_CONFIRM_PREFIX));
		} else if (id.startsWith(CLOSE_PREFIX)) {
			askCloseConfirmation(event, parseId(id, CLOSE_PREFIX));
		} else if (id.equals(CLOSE_ABORT)) {
			event.editMessage(new net.dv8tion.jda.api.utils.messages.MessageEditBuilder()
					.setContent("Abgebrochen.").setComponents(List.of()).build()).queue();
		}
	}

	@Override
	public void onStringSelectInteraction(@Nonnull StringSelectInteractionEvent event) {
		if (!event.getComponentId().startsWith(MenuRenderer.SELECT_PREFIX)) {
			return;
		}
		final String value = event.getValues().isEmpty() ? null : event.getValues().get(0);
		if (value == null) {
			return;
		}
		// Das Dropdown behaelt sonst die getroffene Auswahl sichtbar stehen.
		event.getMessage().editMessageComponents(event.getMessage().getComponents()).queue();
		handleOpen(event, Long.parseLong(value));
	}

	// -----------------------------------------------------------------------
	// Oeffnen
	// -----------------------------------------------------------------------

	private void handleOpen(IReplyCallback event, long panelId) {
		final Guild guild = event.getGuild();
		final Member member = event.getMember();
		if (guild == null || member == null) {
			return;
		}

		// Nur der Klickende sieht die Antwort - eine Fehlermeldung wie "du hast
		// schon ein Ticket" geht niemanden sonst etwas an.
		//
		// Die Arbeit startet erst im Callback der Quittung. Vorher loszulaufen
		// ist ein Rennen: queue() bestaetigt asynchron, und wenn der Hook
		// benutzt wird, bevor die Bestaetigung bei Discord angekommen ist,
		// antwortet Discord mit 10062 "Unknown interaction".
		event.deferReply(true).queue(hook -> new Thread(() -> {
			final Optional<Panel> maybePanel = PanelDao.byId(panelId);
			if (maybePanel.isEmpty()) {
				event.getHook().editOriginalEmbeds(
						MessageUtil.error("Dieser Ticket-Typ existiert nicht mehr.")).queue();
				return;
			}
			final Panel panel = maybePanel.get();

			final TicketService.OpenResult result;
			try {
				result = TicketService.open(guild, panel, member);
			} catch (final RuntimeException e) {
				System.err.println("Ticket konnte nicht geoeffnet werden: " + e);
				event.getHook().editOriginalEmbeds(
						MessageUtil.error("Beim Anlegen ist etwas schiefgegangen. Bitte melde dich bei der Orga."))
						.queue();
				return;
			}

			if (!result.ok()) {
				event.getHook().editOriginalEmbeds(MessageUtil.error(result.error())).queue();
				return;
			}

			postWelcome(result.channel(), panel, member);

			if (panel.dmOnOpen()) {
				Dm.send(member.getUser(), MessageUtil.embed("Ticket geöffnet",
						"Dein Ticket **" + result.channel().getName() + "** auf **"
								+ guild.getName() + "** ist offen. Wir melden uns dort.",
						GuildConfigDao.get(guild.getId()).ticketEmbedColor()));
			}
			event.getHook().editOriginalEmbeds(MessageUtil.embed(null,
					"Dein Ticket wurde geoeffnet: " + result.channel().getAsMention(),
					GuildConfigDao.get(guild.getId()).ticketEmbedColor())).queue();
		}, "ticket-open-" + member.getId()).start());
	}

	/** Die erste Nachricht im frisch angelegten Ticket. */
	private void postWelcome(TextChannel channel, Panel panel, Member owner) {
		final GuildConfig config = GuildConfigDao.get(panel.guildId());

		final List<String> mentions = new ArrayList<>();
		// Den Eroeffner nur dann separat anpingen, wenn der Willkommenstext ihn
		// nicht ohnehin schon erwaehnt. Sonst steht er zweimal in derselben
		// Nachricht — "@Simon\nHey @Simon, danke fuer deine Bewerbung" — und
		// bekommt zwei Benachrichtigungen fuer ein Ticket.
		if (!erwaehntEroeffner(panel.welcomeText())) {
			mentions.add(owner.getAsMention());
		}
		for (final String roleId : PanelDao.pingRoles(panel.id())) {
			final var role = channel.getGuild().getRoleById(roleId);
			if (role != null) {
				mentions.add(role.getAsMention());
			}
		}

		final String body = MessageUtil.fill(panel.welcomeText(), owner.getAsMention(), panel.name());
		final StringBuilder content = new StringBuilder(String.join(" ", mentions));
		if (!body.isBlank()) {
			if (content.length() > 0) {
				content.append('\n');
			}
			content.append(body);
		}

		final String title = panel.welcomeEmbedTitle() != null && !panel.welcomeEmbedTitle().isBlank()
				? panel.welcomeEmbedTitle()
				: panel.name();

		channel.sendMessage(new MessageCreateBuilder()
				.setContent(content.toString())
				.setEmbeds(MessageUtil.embed(title,
						MessageUtil.fill(panel.welcomeEmbedText(), owner.getAsMention(), panel.name()),
						config.ticketEmbedColor()))
				.setComponents(ActionRow.of(closeButton(channel)))
				.build())
				.queue();
	}

	/**
	 * Erwaehnt der Willkommenstext den Eroeffner selbst?
	 *
	 * Sowohl {@code {user}} als Platzhalter als auch eine bereits eingesetzte
	 * Erwaehnung zaehlen — letzteres kommt vor, wenn ein Text aus einer
	 * bestehenden Nachricht uebernommen wurde.
	 */
	static boolean erwaehntEroeffner(String willkommenstext) {
		if (willkommenstext == null || willkommenstext.isBlank()) {
			return false;
		}
		return willkommenstext.contains("{user}") || willkommenstext.matches("(?s).*<@!?\\d+>.*");
	}

	private Button closeButton(TextChannel channel) {
		final long ticketId = TicketDao.byChannel(channel.getId()).map(Ticket::id).orElse(0L);
		return Button.secondary(CLOSE_PREFIX + ticketId, "Schließen").withEmoji(Emoji.fromUnicode("🔒"));
	}

	// -----------------------------------------------------------------------
	// Schliessen
	// -----------------------------------------------------------------------

	/**
	 * Schliessen ist nicht umkehrbar ohne Aufwand, deshalb erst nachfragen.
	 * Ticket Tool macht das genauso.
	 */
	private void askCloseConfirmation(ButtonInteractionEvent event, long ticketId) {
		event.reply("Soll dieses Ticket wirklich geschlossen werden?")
				.setEphemeral(true)
				.setComponents(ActionRow.of(
						Button.danger(CLOSE_CONFIRM_PREFIX + ticketId, "Ja, schließen"),
						Button.secondary(CLOSE_ABORT, "Abbrechen")))
				.queue();
	}

	private void handleCloseConfirmed(ButtonInteractionEvent event, long ticketId) {
		final Guild guild = event.getGuild();
		final Member closer = event.getMember();
		if (guild == null || closer == null) {
			return;
		}
		// Siehe handleOpen: erst die Quittung abwarten, dann arbeiten.
		event.deferEdit().queue(hook -> new Thread(() -> {
			final Optional<Ticket> maybeTicket = TicketDao.byId(ticketId);
			if (maybeTicket.isEmpty() || !maybeTicket.get().isOpen()) {
				event.getHook().editOriginal("Dieses Ticket ist bereits geschlossen.")
						.setComponents(List.of()).queue();
				return;
			}
			final Ticket ticket = maybeTicket.get();
			final Optional<Panel> panel = ticket.panelId() == null
					? Optional.empty()
					: PanelDao.byId(ticket.panelId());
			if (panel.isEmpty()) {
				event.getHook().editOriginal("Das zugehoerige Panel existiert nicht mehr — bitte manuell schliessen.")
						.setComponents(List.of()).queue();
				return;
			}

			try {
				TicketService.close(guild, ticket, panel.get(), closer.getId(), null);
				// Nach dem Schliessen neu laden: der Kanalname hat sich geaendert,
				// und genau der soll im Archiv und im Log stehen.
				TicketDao.byId(ticketId).ifPresent(
						closed -> TranscriptArchiver.archive(guild, closed, panel.get(), closer.getId()));
				event.getHook().editOriginal("Ticket geschlossen.").setComponents(List.of()).queue();
			} catch (final RuntimeException e) {
				System.err.println("Ticket " + ticketId + " konnte nicht geschlossen werden: " + e);
				event.getHook().editOriginal("Das Schliessen ist fehlgeschlagen: " + e.getMessage())
						.setComponents(List.of()).queue();
			}
		}, "ticket-close-" + ticketId).start());
	}

	// -----------------------------------------------------------------------
	// Loeschen
	// -----------------------------------------------------------------------

	/**
	 * Fuehrt das Loeschen aus, nachdem im Befehl nachgefragt wurde.
	 *
	 * Die Antwort geht raus, BEVOR der Kanal faellt: sie haengt an genau diesem
	 * Kanal und laesst sich danach nicht mehr bearbeiten.
	 */
	private void handleDeleteConfirmed(ButtonInteractionEvent event, long ticketId) {
		final Guild guild = event.getGuild();
		final Member member = event.getMember();
		if (guild == null || member == null) {
			return;
		}

		event.deferEdit().queue(hook -> new Thread(() -> {
			final Optional<Ticket> maybeTicket = TicketDao.byId(ticketId);
			if (maybeTicket.isEmpty()) {
				event.getHook().editOriginal("Dieses Ticket gibt es nicht mehr.")
						.setComponents(List.of()).queue();
				return;
			}
			final Ticket ticket = maybeTicket.get();

			// Zweite Pruefung: die Frage sah nur der Aufrufer, aber zwischen
			// Frage und Klick kann sich seine Rolle geaendert haben.
			if (!Visibility.darfVerwalten(guild, ticket, member)) {
				event.getHook().editOriginal("Dafür fehlt dir die Berechtigung.")
						.setComponents(List.of()).queue();
				return;
			}

			final Panel panel = ticket.panelId() == null
					? null
					: PanelDao.byId(ticket.panelId()).orElse(null);

			try {
				event.getHook().editOriginal("Verlauf wird gesichert, dann fällt der Kanal.")
						.setComponents(List.of()).complete();
				TicketService.delete(guild, ticket, panel, member.getId());
				System.out.println("Ticket " + ticket.channelName() + " gelöscht von "
						+ member.getUser().getName());
			} catch (final RuntimeException e) {
				System.err.println("Ticket " + ticketId + " konnte nicht gelöscht werden: " + e);
				event.getHook().editOriginal("Das Löschen ist fehlgeschlagen: " + e.getMessage())
						.setComponents(List.of()).queue(ok -> {
						}, err -> {
						});
			}
		}, "ticket-delete-" + ticketId).start());
	}

	private static long parseId(String componentId, String prefix) {
		try {
			return Long.parseLong(componentId.substring(prefix.length()));
		} catch (final NumberFormatException e) {
			return -1;
		}
	}
}
