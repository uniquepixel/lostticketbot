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
import transcript.TranscriptAusgabe;
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
	public static final String DELETE_PREFIX = "tb:delete:";
	public static final String REOPEN_PREFIX = "tb:reopen:";
	public static final String TRANSCRIPT_PREFIX = "tb:transcript:";

	@Override
	public void onButtonInteraction(@Nonnull ButtonInteractionEvent event) {
		final String id = event.getComponentId();

		if (id.startsWith(MenuRenderer.OPEN_PREFIX)) {
			handleOpen(event, parseId(id, MenuRenderer.OPEN_PREFIX));
		} else if (id.startsWith(DELETE_CONFIRM_PREFIX)) {
			handleDeleteConfirmed(event, parseId(id, DELETE_CONFIRM_PREFIX));
		} else if (id.startsWith(DELETE_PREFIX)) {
			askDeleteConfirmation(event, parseId(id, DELETE_PREFIX));
		} else if (id.startsWith(REOPEN_PREFIX)) {
			handleReopen(event, parseId(id, REOPEN_PREFIX));
		} else if (id.startsWith(TRANSCRIPT_PREFIX)) {
			handleTranscript(event, parseId(id, TRANSCRIPT_PREFIX));
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

		// Der einzige Ort, an dem ein Ping gewollt ist: der Eroeffner und die
		// zustaendigen Rollen sollen mitbekommen, dass es losgeht. Ueberall
		// sonst gilt die stille Voreinstellung aus Bot.main.
		channel.sendMessage(new MessageCreateBuilder()
				.setAllowedMentions(java.util.EnumSet.of(
						net.dv8tion.jda.api.entities.Message.MentionType.USER,
						net.dv8tion.jda.api.entities.Message.MentionType.ROLE))
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
				TicketDao.byId(ticketId).ifPresent(closed -> {
					TranscriptArchiver.archive(guild, closed, panel.get(), closer.getId());
					final TextChannel kanal = guild.getTextChannelById(closed.channelId());
					if (kanal != null) {
						postCloseNotice(kanal, closed, closer.getId());
					}
				});
				event.getHook().editOriginal("Ticket geschlossen.").setComponents(List.of()).queue();
			} catch (final RuntimeException e) {
				System.err.println("Ticket " + ticketId + " konnte nicht geschlossen werden: " + e);
				event.getHook().editOriginal("Das Schliessen ist fehlgeschlagen: " + e.getMessage())
						.setComponents(List.of()).queue();
			}
		}, "ticket-close-" + ticketId).start());
	}

	/**
	 * Die Nachricht, die im geschlossenen Ticket stehen bleibt.
	 *
	 * Bewusst eine gewoehnliche Kanalnachricht und keine fluechtige Antwort:
	 * sie gilt allen im Ticket und steht morgen noch da. Daran haengen die
	 * Knoepfe — Transcript holen, wieder oeffnen, loeschen. Ticket Tool macht
	 * es genauso, und niemand soll sich dafuer einen Befehl merken muessen.
	 *
	 * Der Loeschknopf ist fuer alle sichtbar, wirkt aber nur beim Team. Ihn zu
	 * verstecken hiesse, ihn an die Rolle des Betrachters zu binden — dieselbe
	 * Nachricht kann Discord aber nicht zwei Leuten verschieden zeigen.
	 */
	public static void postCloseNotice(TextChannel channel, Ticket ticket, String closerId) {
		final GuildConfig config = GuildConfigDao.get(ticket.guildId());
		final String von = closerId == null
				? "Automatisch geschlossen"
				: "Geschlossen von <@" + closerId + ">";

		channel.sendMessageEmbeds(MessageUtil.embed("Ticket geschlossen",
				von + ".\n\nDer Verlauf ist gesichert. **Löschen** entfernt den Kanal endgültig — "
						+ "das Transcript bleibt auch danach abrufbar.",
				config.ticketEmbedColor()))
				.setComponents(ActionRow.of(
						Button.secondary(TRANSCRIPT_PREFIX + ticket.id(), "Transcript")
								.withEmoji(Emoji.fromUnicode("\uD83D\uDCC4")),
						Button.success(REOPEN_PREFIX + ticket.id(), "Wieder öffnen")
								.withEmoji(Emoji.fromUnicode("\uD83D\uDD13")),
						Button.danger(DELETE_PREFIX + ticket.id(), "Löschen")
								.withEmoji(Emoji.fromUnicode("\uD83D\uDDD1"))))
				.queue(ok -> {
				}, err -> System.err.println("Schliessnachricht in " + ticket.channelName()
						+ " fehlgeschlagen: " + err.getMessage()));
	}

	// -----------------------------------------------------------------------
	// Transcript und Wiedereroeffnen ueber die Knoepfe
	// -----------------------------------------------------------------------

	private void handleTranscript(ButtonInteractionEvent event, long ticketId) {
		final Guild guild = event.getGuild();
		if (guild == null) {
			return;
		}
		// Nur fuer den Klickenden: die Datei enthaelt den ganzen Verlauf.
		event.deferReply(true).queue(hook -> new Thread(() -> {
			final Optional<Ticket> ticket = TicketDao.byId(ticketId);
			if (ticket.isEmpty()) {
				hook.editOriginalEmbeds(MessageUtil.error("Dieses Ticket gibt es nicht mehr.")).queue();
				return;
			}
			try {
				TranscriptAusgabe.senden(hook, guild, ticket.get(), event.getUser().getId());
			} catch (final RuntimeException e) {
				System.err.println("Transcript fuer " + ticketId + " fehlgeschlagen: " + e);
				hook.editOriginalEmbeds(MessageUtil.error(
						"Das Transcript konnte nicht erzeugt werden.")).queue();
			}
		}, "transcript-" + ticketId).start());
	}

	private void handleReopen(ButtonInteractionEvent event, long ticketId) {
		final Guild guild = event.getGuild();
		final Member member = event.getMember();
		if (guild == null || member == null) {
			return;
		}

		event.deferReply(true).queue(hook -> new Thread(() -> {
			final Optional<Ticket> maybeTicket = TicketDao.byId(ticketId);
			if (maybeTicket.isEmpty()) {
				hook.editOriginalEmbeds(MessageUtil.error("Dieses Ticket gibt es nicht mehr.")).queue();
				return;
			}
			final Ticket ticket = maybeTicket.get();

			if (!Visibility.darfVerwalten(guild, ticket, member)) {
				hook.editOriginalEmbeds(MessageUtil.error(
						"Tickets wieder öffnen darf nur das Team dieses Bereichs.")).queue();
				return;
			}
			if (ticket.isOpen()) {
				hook.editOriginalEmbeds(MessageUtil.error("Dieses Ticket ist bereits offen.")).queue();
				return;
			}
			final Optional<Panel> panel = ticket.panelId() == null
					? Optional.empty()
					: PanelDao.byId(ticket.panelId());
			if (panel.isEmpty()) {
				hook.editOriginalEmbeds(MessageUtil.error(
						"Das zugehörige Panel gibt es nicht mehr.")).queue();
				return;
			}

			try {
				TicketService.reopen(guild, ticket, panel.get());
				// Die Knoepfe an dieser Nachricht passen jetzt nicht mehr: das
				// Ticket ist offen, und ein Loeschknopf mitten im laufenden
				// Gespraech ist eine Falle.
				event.getMessage().editMessageComponents(List.of()).queue(ok -> {
				}, err -> {
				});
				hook.editOriginalEmbeds(MessageUtil.embed(null,
						"Ticket wieder geöffnet.", 0x1ec45c)).queue();
			} catch (final RuntimeException e) {
				System.err.println("Wiedereroeffnen von " + ticketId + " fehlgeschlagen: " + e);
				hook.editOriginalEmbeds(MessageUtil.error(
						"Wiedereröffnen fehlgeschlagen: " + e.getMessage())).queue();
			}
		}, "ticket-reopen-" + ticketId).start());
	}

	// -----------------------------------------------------------------------
	// Loeschen
	// -----------------------------------------------------------------------

	/**
	 * Fragt nach, bevor geloescht wird — dieselbe Rueckfrage wie bei
	 * {@code /ticket loeschen}, nur ueber den Knopf ausgeloest.
	 *
	 * Die Rueckfrage sieht nur der Klickende; die Schliessnachricht bleibt fuer
	 * alle stehen.
	 */
	private void askDeleteConfirmation(ButtonInteractionEvent event, long ticketId) {
		final Guild guild = event.getGuild();
		final Member member = event.getMember();
		if (guild == null || member == null) {
			return;
		}
		final Optional<Ticket> ticket = TicketDao.byId(ticketId);
		if (ticket.isEmpty()) {
			event.replyEmbeds(MessageUtil.error("Dieses Ticket gibt es nicht mehr."))
					.setEphemeral(true).queue();
			return;
		}
		if (!Visibility.darfVerwalten(guild, ticket.get(), member)) {
			event.replyEmbeds(MessageUtil.error(
					"Tickets löschen darf nur das Team dieses Bereichs."))
					.setEphemeral(true).queue();
			return;
		}

		event.replyEmbeds(MessageUtil.error(
				"**" + ticket.get().channelName() + "** wirklich löschen?\n\n"
						+ "Der Verlauf wird vorher gesichert und bleibt über "
						+ "`/transcript holen id:" + ticketId + "` abrufbar.\n\n"
						+ "Der Kanal selbst ist danach weg — das lässt sich nicht rückgängig machen."))
				.setEphemeral(true)
				.setComponents(ActionRow.of(
						Button.danger(DELETE_CONFIRM_PREFIX + ticketId, "Ja, löschen"),
						Button.secondary(CLOSE_ABORT, "Abbrechen")))
				.queue();
	}

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
