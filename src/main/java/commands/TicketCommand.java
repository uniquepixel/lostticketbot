package commands;

import java.util.List;
import java.util.Optional;

import javax.annotation.Nonnull;

import db.PanelDao;
import db.TicketDao;
import model.Panel;
import model.Ticket;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import ticket.TicketInteractions;
import ticket.TicketService;
import ticket.Visibility;
import util.MessageUtil;

/**
 * Befehle innerhalb eines Tickets: beanspruchen, Leute dazuholen, umbenennen,
 * wieder oeffnen.
 *
 * Alle gelten nur im Ticketkanal selbst — der Kanal sagt, um welches Ticket es
 * geht, so muss niemand eine Nummer eintippen.
 */
public class TicketCommand extends ListenerAdapter {

	public static final String NAME = "ticket";

	private static final long TICKET_ACCESS_ALLOW = Permission.getRaw(
			Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_HISTORY,
			Permission.MESSAGE_ATTACH_FILES, Permission.MESSAGE_EMBED_LINKS);

	@Override
	public void onSlashCommandInteraction(@Nonnull SlashCommandInteractionEvent event) {
		if (!NAME.equals(event.getName()) || event.getGuild() == null) {
			return;
		}

		final Optional<Ticket> maybe = TicketDao.byChannel(event.getChannel().getId());
		if (maybe.isEmpty()) {
			event.replyEmbeds(MessageUtil.error("Das hier ist kein Ticketkanal."))
					.setEphemeral(true).queue();
			return;
		}
		final Ticket ticket = maybe.get();

		switch (String.valueOf(event.getSubcommandName())) {
			case "beanspruchen" -> claim(event, ticket);
			case "freigeben" -> unclaim(event, ticket);
			case "hinzufuegen" -> addUser(event, ticket);
			case "entfernen" -> removeUser(event, ticket);
			case "umbenennen" -> rename(event, ticket);
			case "wiedereroeffnen" -> reopen(event, ticket);
			case "loeschen" -> delete(event, ticket);
			case "info" -> info(event, ticket);
			default -> event.reply("Unbekannter Unterbefehl.").setEphemeral(true).queue();
		}
	}

	private void claim(SlashCommandInteractionEvent event, Ticket ticket) {
		final Member member = event.getMember();
		if (ticket.claimedBy() != null) {
			event.replyEmbeds(MessageUtil.error(
					"Dieses Ticket betreut bereits <@" + ticket.claimedBy() + ">."))
					.setEphemeral(true).queue();
			return;
		}
		TicketDao.setClaim(ticket.id(), member.getId());
		// Bewusst sichtbar für alle im Ticket: der Eröffner soll wissen, wer
		// sich kümmert, und andere Teammitglieder, dass es jemand tut.
		event.replyEmbeds(MessageUtil.embed(null,
				member.getAsMention() + " kümmert sich um dieses Ticket.", 0x1ec45c)).queue();
	}

	private void unclaim(SlashCommandInteractionEvent event, Ticket ticket) {
		if (ticket.claimedBy() == null) {
			event.replyEmbeds(MessageUtil.error("Dieses Ticket betreut niemand."))
					.setEphemeral(true).queue();
			return;
		}
		TicketDao.setClaim(ticket.id(), null);
		event.replyEmbeds(MessageUtil.embed(null,
				"Das Ticket ist wieder frei.", 0x1ec45c)).queue();
	}

	private void addUser(SlashCommandInteractionEvent event, Ticket ticket) {
		final User user = event.getOption("user", null, OptionMapping::getAsUser);
		if (user == null) {
			return;
		}
		final TextChannel channel = (TextChannel) event.getChannel();

		event.deferReply().queue(hook -> event.getGuild().retrieveMember(user).queue(
				member -> channel.upsertPermissionOverride(member)
						.setAllowed(TICKET_ACCESS_ALLOW)
						.reason("Von " + event.getUser().getName() + " zum Ticket hinzugefügt")
						.queue(ok -> {
							TicketDao.addMember(ticket.id(), user.getId(), event.getUser().getId());
							hook.editOriginalEmbeds(MessageUtil.embed(null,
									user.getAsMention() + " wurde hinzugefügt.", 0x1ec45c)).queue();
						}, err -> hook.editOriginalEmbeds(MessageUtil.error(
								"Zugriff konnte nicht gesetzt werden: " + err.getMessage())).queue()),
				err -> hook.editOriginalEmbeds(MessageUtil.error(
						"Diese Person ist nicht auf dem Server.")).queue()));
	}

	private void removeUser(SlashCommandInteractionEvent event, Ticket ticket) {
		final User user = event.getOption("user", null, OptionMapping::getAsUser);
		if (user == null) {
			return;
		}
		if (user.getId().equals(ticket.ownerId())) {
			event.replyEmbeds(MessageUtil.error(
					"Den Eröffner kann man nicht aus seinem eigenen Ticket entfernen."))
					.setEphemeral(true).queue();
			return;
		}
		final TextChannel channel = (TextChannel) event.getChannel();

		event.deferReply().queue(hook -> event.getGuild().retrieveMember(user).queue(
				member -> channel.getManager().removePermissionOverride(member)
						.reason("Von " + event.getUser().getName() + " entfernt")
						.queue(ok -> {
							TicketDao.removeMember(ticket.id(), user.getId());
							hook.editOriginalEmbeds(MessageUtil.embed(null,
									user.getAsMention() + " wurde entfernt.", 0x1ec45c)).queue();
						}, err -> hook.editOriginalEmbeds(MessageUtil.error(
								"Zugriff konnte nicht entzogen werden: " + err.getMessage())).queue()),
				err -> hook.editOriginalEmbeds(MessageUtil.error(
						"Diese Person ist nicht auf dem Server.")).queue()));
	}

	private void rename(SlashCommandInteractionEvent event, Ticket ticket) {
		final String wunsch = event.getOption("name", "", OptionMapping::getAsString);
		final String name = Panel.sanitizeChannelName(wunsch);
		if (name.isBlank()) {
			event.replyEmbeds(MessageUtil.error("Dieser Name bleibt nach Discords Regeln leer."))
					.setEphemeral(true).queue();
			return;
		}

		event.deferReply().queue(hook -> event.getChannel().asTextChannel().getManager().setName(name)
				.reason("Umbenannt von " + event.getUser().getName())
				.queue(ok -> {
					TicketDao.setChannelName(ticket.id(), name);
					hook.editOriginalEmbeds(MessageUtil.embed(null,
							"Kanal heißt jetzt `" + name + "`."
									+ (name.equals(wunsch) ? ""
											: "\n\nDiscord erlaubt keine Großbuchstaben und keine Leerzeichen "
													+ "in Kanalnamen — daraus wurde `" + name + "`."),
							0x1ec45c)).queue();
				}, err -> hook.editOriginalEmbeds(MessageUtil.error(
						"Umbenennen fehlgeschlagen: " + err.getMessage())).queue()));
	}

	private void reopen(SlashCommandInteractionEvent event, Ticket ticket) {
		if (ticket.isOpen()) {
			event.replyEmbeds(MessageUtil.error("Dieses Ticket ist bereits offen."))
					.setEphemeral(true).queue();
			return;
		}
		final Optional<Panel> panel = ticket.panelId() == null
				? Optional.empty()
				: PanelDao.byId(ticket.panelId());
		if (panel.isEmpty()) {
			event.replyEmbeds(MessageUtil.error("Das zugehörige Panel gibt es nicht mehr."))
					.setEphemeral(true).queue();
			return;
		}
		final Guild guild = event.getGuild();

		event.deferReply().queue(hook -> new Thread(() -> {
			try {
				TicketService.reopen(guild, ticket, panel.get());
				hook.editOriginalEmbeds(MessageUtil.embed(null,
						"Ticket wieder geöffnet.", 0x1ec45c)).queue();
			} catch (final RuntimeException e) {
				hook.editOriginalEmbeds(MessageUtil.error(
						"Wiedereröffnen fehlgeschlagen: " + e.getMessage())).queue();
			}
		}, "ticket-reopen-" + ticket.id()).start());
	}

	/**
	 * Fragt nach, bevor geloescht wird. Ausgefuehrt wird in
	 * {@link ticket.TicketInteractions} — dort haengt schon die Bestaetigung
	 * fuers Schliessen.
	 *
	 * Loeschen ist die einzige Ticketaktion, die nicht jeder im Kanal darf. Der
	 * Eroeffner sieht sein Ticket, aber er soll es nicht verschwinden lassen
	 * koennen: gerade bei einer Beschwerde ist die Aufzeichnung das, worauf
	 * sich die Orga spaeter beruft.
	 */
	private void delete(SlashCommandInteractionEvent event, Ticket ticket) {
		if (!Visibility.darfVerwalten(event.getGuild(), ticket, event.getMember())) {
			event.replyEmbeds(MessageUtil.error(
					"Tickets löschen darf nur das Team dieses Bereichs."))
					.setEphemeral(true).queue();
			return;
		}

		final String hinweis = "Der Verlauf wird vorher gesichert und bleibt über "
				+ "`/transcript holen id:" + ticket.id() + "` abrufbar.";

		event.replyEmbeds(MessageUtil.error(
				"**" + ticket.channelName() + "** wirklich löschen?\n\n"
						+ hinweis + "\n\nDer Kanal selbst ist danach weg — das lässt sich nicht "
						+ "rückgängig machen."))
				.setEphemeral(true)
				.setComponents(ActionRow.of(
						Button.danger(TicketInteractions.DELETE_CONFIRM_PREFIX + ticket.id(),
								"Ja, löschen"),
						Button.secondary(TicketInteractions.CLOSE_ABORT, "Abbrechen")))
				.queue();
	}

	private void info(SlashCommandInteractionEvent event, Ticket ticket) {
		final String panelName = ticket.panelId() == null
				? "—"
				: PanelDao.byId(ticket.panelId()).map(Panel::name).orElse("(gelöscht)");
		final List<String> members = TicketDao.members(ticket.id());

		final String text = """
				**Panel:** %s
				**Nummer:** %d
				**Eröffner:** <@%s>
				**Betreut von:** %s
				**Status:** %s
				**Geöffnet:** %s
				**Zusätzlich im Ticket:** %s"""
				.formatted(panelName, ticket.number(), ticket.ownerId(),
						ticket.claimedBy() == null ? "niemand" : "<@" + ticket.claimedBy() + ">",
						ticket.status().name().toLowerCase(),
						ticket.openedAt(),
						members.isEmpty() ? "niemand"
								: String.join(", ", members.stream().map(m -> "<@" + m + ">").toList()));

		event.replyEmbeds(MessageUtil.embed("Ticket " + ticket.channelName(), text, 0x1ec45c))
				.setEphemeral(true).queue();
	}
}
