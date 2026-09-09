package commands;

import java.util.List;

import javax.annotation.Nonnull;

import db.GuildConfigDao;
import db.TicketDao;
import migration.AdoptionService;
import migration.AdoptionService.Kandidat;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import ticket.TicketInteractions;
import util.MessageUtil;

/**
 * Uebernahme der offenen Tickets aus Ticket Tool.
 *
 * Zwei Schritte mit Absicht: erst "vorschau", die nur liest und auflistet, was
 * passieren wuerde, dann "ausfuehren". Beim Umschalten sind die offenen
 * Tickets echte laufende Gespraeche mit Bewerbern — da soll niemand aus
 * Versehen etwas anfassen.
 */
public class AdoptCommand extends ListenerAdapter {

	public static final String NAME = "uebernehmen";

	@Override
	public void onSlashCommandInteraction(@Nonnull SlashCommandInteractionEvent event) {
		if (!NAME.equals(event.getName()) || event.getGuild() == null) {
			return;
		}
		final boolean nurVorschau = "vorschau".equals(event.getSubcommandName());
		final Guild guild = event.getGuild();

		event.deferReply(true).queue(hook -> new Thread(() -> {
			try {
				lauf(guild, hook, nurVorschau);
			} catch (final RuntimeException e) {
				System.err.println("Uebernahme fehlgeschlagen: " + e);
				hook.editOriginalEmbeds(MessageUtil.error("Abgebrochen: " + e.getMessage())).queue();
			}
		}, "adoption").start());
	}

	private void lauf(Guild guild, InteractionHook hook, boolean nurVorschau) {
		final List<Kandidat> kandidaten = AdoptionService.finden(guild,
				meldung -> hook.editOriginalEmbeds(
						MessageUtil.embed("Übernahme läuft", meldung, 0x1ec45c)).queue());
		if (kandidaten.isEmpty()) {
			hook.editOriginalEmbeds(MessageUtil.embed("Übernahme",
					"Keine Ticketkanäle gefunden, die noch nicht erfasst sind. Erkannt werden "
							+ "Kanäle, in denen Ticket Tool die erste Nachricht geschrieben hat.",
					0x1ec45c)).queue();
			return;
		}

		if (nurVorschau) {
			hook.editOriginalEmbeds(MessageUtil.embed("Vorschau — es wurde nichts geändert",
					bericht(kandidaten), 0x1ec45c)).queue();
			return;
		}

		final var ergebnis = AdoptionService.uebernehmen(guild, kandidaten);
		neueKnoepfe(guild, ergebnis.uebernommen());

		final StringBuilder sb = new StringBuilder();
		sb.append("**Übernommen:** ").append(ergebnis.uebernommen().size()).append('\n');
		sb.append("**Übersprungen:** ").append(ergebnis.uebersprungen().size()).append('\n');
		if (!ergebnis.zaehlerAngepasst().isEmpty()) {
			sb.append("\n**Zähler nachgezogen:**\n");
			ergebnis.zaehlerAngepasst().forEach(z -> sb.append("· ").append(z).append('\n'));
		}
		if (!ergebnis.uebersprungen().isEmpty()) {
			sb.append("\n**Nicht übernommen:**\n");
			ergebnis.uebersprungen().forEach(
					k -> sb.append("· ").append(k.channel().getAsMention()).append(" — ")
							.append(k.hinweis() == null ? "unbekannter Grund" : k.hinweis()).append('\n'));
		}
		sb.append("\nIn jedes übernommene offene Ticket wurde ein neuer Schließen-Knopf gepostet — "
				+ "der alte von Ticket Tool hört auf zu funktionieren, sobald dessen Bot geht.");

		hook.editOriginalEmbeds(MessageUtil.embed("Übernahme fertig", sb.toString(), 0x1ec45c)).queue();
	}

	private String bericht(List<Kandidat> kandidaten) {
		final StringBuilder sb = new StringBuilder();
		final List<Kandidat> gut = kandidaten.stream().filter(Kandidat::uebernehmbar).toList();
		final List<Kandidat> schlecht = kandidaten.stream().filter(k -> !k.uebernehmbar()).toList();

		sb.append("**").append(gut.size()).append(" übernehmbar**\n");
		for (final Kandidat k : gut.subList(0, Math.min(gut.size(), 20))) {
			sb.append("· `").append(k.channel().getName()).append("` → ")
					.append(k.panel().name()).append(" Nr. ").append(k.nummer())
					.append(k.geschlossen() ? " _(geschlossen)_" : "")
					.append(" · <@").append(k.ownerId()).append(">");
			if (k.hinweis() != null) {
				sb.append(" · ").append(k.hinweis());
			}
			sb.append('\n');
		}
		if (gut.size() > 20) {
			sb.append("… und ").append(gut.size() - 20).append(" weitere\n");
		}
		if (!schlecht.isEmpty()) {
			sb.append("\n**").append(schlecht.size()).append(" ohne erkennbaren Eröffner**\n");
			schlecht.forEach(k -> sb.append("· `").append(k.channel().getName()).append("`\n"));
		}
		sb.append("\nMit `/uebernehmen ausfuehren` tatsächlich übernehmen.");
		return sb.toString();
	}

	/**
	 * Postet in jedes uebernommene offene Ticket einen frischen Schliessen-Knopf.
	 * Der von Ticket Tool ist danach nur noch Dekoration.
	 */
	private void neueKnoepfe(Guild guild, List<Kandidat> uebernommen) {
		final int farbe = GuildConfigDao.get(guild.getId()).ticketEmbedColor();
		for (final Kandidat k : uebernommen) {
			if (k.geschlossen()) {
				continue;
			}
			TicketDao.byChannel(k.channel().getId()).ifPresent(ticket -> k.channel()
					.sendMessage(new MessageCreateBuilder()
							.setEmbeds(MessageUtil.embed(null,
									"Dieses Ticket wird ab jetzt vom neuen Ticketsystem verwaltet. "
											+ "Bitte diesen Knopf zum Schließen benutzen.",
									farbe))
							.setComponents(ActionRow.of(Button
									.secondary(TicketInteractions.CLOSE_PREFIX + ticket.id(), "Schließen")
									.withEmoji(Emoji.fromUnicode("🔒"))))
							.build())
					.queue(ok -> {
					}, err -> System.err.println("Knopf in " + k.channel().getName()
							+ " konnte nicht gepostet werden: " + err.getMessage())));
		}
	}
}
