package commands;

import java.util.List;
import java.util.Optional;

import javax.annotation.Nonnull;

import db.TicketDao;
import db.Database;
import model.Panel;
import model.Ticket;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import ticket.Visibility;
import transcript.TranscriptAusgabe;
import util.MessageUtil;

/**
 * Gibt den Verlauf eines Tickets als lesbare HTML-Datei aus.
 *
 * Ersetzt Ticket Tools Transcript-Ansicht, bis das Dashboard auf der Website
 * steht. Bewusst hier im Bot und nicht als schnelle Website-Seite: die Website
 * wird ohnehin umgebaut, dieser Befehl bleibt davon unberuehrt.
 *
 * Die Antwort ist immer nur fuer den Aufrufenden sichtbar — ein Transcript
 * enthaelt Screenshots und Gespraeche, die nicht in einen offenen Kanal
 * gehoeren.
 */
public class TranscriptCommand extends ListenerAdapter {

	public static final String NAME = "transcript";

	@Override
	public void onSlashCommandInteraction(@Nonnull SlashCommandInteractionEvent event) {
		if (!NAME.equals(event.getName()) || event.getGuild() == null) {
			return;
		}
		final Guild guild = event.getGuild();

		event.deferReply(true).queue(hook -> new Thread(() -> {
			try {
				switch (String.valueOf(event.getSubcommandName())) {
					case "hier" -> hier(event, hook, guild);
					case "holen" -> holen(event, hook, guild);
					case "suchen" -> suchen(event, hook, guild);
					default -> hook.editOriginalEmbeds(
							MessageUtil.error("Unbekannter Unterbefehl.")).queue();
				}
			} catch (final RuntimeException e) {
				System.err.println("Transcript fehlgeschlagen: " + e);
				hook.editOriginalEmbeds(MessageUtil.error(
						"Das Transcript konnte nicht erzeugt werden: " + e.getMessage())).queue();
			}
		}, "transcript").start());
	}

	private void hier(SlashCommandInteractionEvent event, InteractionHook hook, Guild guild) {
		final Optional<Ticket> ticket = TicketDao.byChannel(event.getChannel().getId());
		if (ticket.isEmpty()) {
			hook.editOriginalEmbeds(MessageUtil.error(
					"Das hier ist kein Ticketkanal. Nutze `/transcript holen` mit einer Ticket-ID "
							+ "oder `/transcript suchen`.")).queue();
			return;
		}
		ausgeben(hook, guild, ticket.get(), event.getUser().getId());
	}

	private void holen(SlashCommandInteractionEvent event, InteractionHook hook, Guild guild) {
		final long id = event.getOption("id", 0L, OptionMapping::getAsLong);
		final Optional<Ticket> ticket = TicketDao.byId(id).filter(t -> t.guildId().equals(guild.getId()));
		if (ticket.isEmpty()) {
			// Bewusst dieselbe Meldung wie bei fehlender Berechtigung: wer ein
			// Ticket nicht sehen darf, soll nicht durch Ausprobieren erfahren,
			// welche IDs es gibt.
			hook.editOriginalEmbeds(MessageUtil.error("Kein Ticket mit dieser ID gefunden.")).queue();
			return;
		}
		ausgeben(hook, guild, ticket.get(), event.getUser().getId());
	}

	private void suchen(SlashCommandInteractionEvent event, InteractionHook hook, Guild guild) {
		final String suche = event.getOption("suche", "", OptionMapping::getAsString).trim();
		if (suche.length() < 2) {
			hook.editOriginalEmbeds(MessageUtil.error("Bitte mindestens zwei Zeichen suchen.")).queue();
			return;
		}

		final List<Panel> sichtbar = Visibility.sichtbarePanels(guild, event.getUser().getId());
		if (sichtbar.isEmpty()) {
			hook.editOriginalEmbeds(MessageUtil.error(
					"Du hast keinen Zugriff auf Tickets auf diesem Server.")).queue();
			return;
		}

		final List<Object> werte = new java.util.ArrayList<>();
		werte.add(guild.getId());
		sichtbar.forEach(p -> werte.add(p.id()));
		werte.add("%" + suche + "%");
		werte.add(suche);

		final String platzhalter = String.join(",", java.util.Collections.nCopies(sichtbar.size(), "?"));
		final List<String> treffer = Database.query(
				"SELECT t.id, t.channel_name, t.status, t.owner_id, p.name AS panel "
						+ "FROM tickets t LEFT JOIN panels p ON p.id = t.panel_id "
						+ "WHERE t.guild_id = ? AND t.panel_id IN (" + platzhalter + ") "
						+ "AND (t.channel_name ILIKE ? OR t.owner_id = ?) "
						+ "ORDER BY t.opened_at DESC LIMIT 15",
				rs -> "`" + rs.getLong("id") + "` · **" + rs.getString("channel_name") + "** · "
						+ rs.getString("panel") + " · " + rs.getString("status")
						+ " · <@" + rs.getString("owner_id") + ">",
				werte.toArray());

		hook.editOriginalEmbeds(MessageUtil.embed(
				treffer.isEmpty() ? "Nichts gefunden" : treffer.size() + " Treffer",
				treffer.isEmpty()
						? "Kein Ticket mit „" + suche + "“ im Namen, und keine Ticket-ID zu dieser "
								+ "Nutzer-ID.\n\nAlt-Tickets aus Ticket Tool stehen weiterhin als "
								+ "HTML-Anhang in den Log-Kanälen."
						: String.join("\n", treffer)
								+ "\n\nMit `/transcript holen id:<Zahl>` abrufen.",
				0x1ec45c)).queue();
	}

	private void ausgeben(InteractionHook hook, Guild guild, Ticket ticket, String anfragenderId) {
		TranscriptAusgabe.senden(hook, guild, ticket, anfragenderId);
	}
}
