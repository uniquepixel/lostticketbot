package commands;

import java.util.List;
import java.util.Optional;

import javax.annotation.Nonnull;

import db.MenuDao;
import db.PanelDao;
import model.Menu;
import model.MenuEntry;
import model.Panel;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import panel.MenuRenderer;
import util.MessageUtil;

/**
 * Verwaltung der Menues — der Nachrichten, ueber die Tickets geoeffnet werden.
 *
 * Ein Menue bietet ein oder mehrere Panels an, als Knopfreihe oder als
 * Dropdown. Panels aus dem Menue zu nehmen legt sie still, ohne sie oder ihre
 * Tickets zu loeschen.
 */
public class MenuCommand extends ListenerAdapter {

	public static final String NAME = "menu";

	@Override
	public void onSlashCommandInteraction(@Nonnull SlashCommandInteractionEvent event) {
		if (!NAME.equals(event.getName()) || event.getGuild() == null) {
			return;
		}
		final String guildId = event.getGuild().getId();

		switch (String.valueOf(event.getSubcommandName())) {
			case "erstellen" -> create(event, guildId);
			case "text" -> text(event, guildId);
			case "hinzufuegen" -> addPanel(event, guildId);
			case "entfernen" -> removePanel(event, guildId);
			case "posten" -> post(event, guildId);
			case "liste" -> list(event, guildId);
			default -> event.reply("Unbekannter Unterbefehl.").setEphemeral(true).queue();
		}
	}

	private void create(SlashCommandInteractionEvent event, String guildId) {
		final var channel = event.getOption("kanal").getAsChannel();
		if (!(channel instanceof TextChannel text)) {
			event.replyEmbeds(MessageUtil.error("Das muss ein Textkanal sein.")).setEphemeral(true).queue();
			return;
		}
		final Menu.Style style = "dropdown".equalsIgnoreCase(
				event.getOption("stil", "knoepfe", OptionMapping::getAsString))
						? Menu.Style.SELECT
						: Menu.Style.BUTTONS;

		final long id = MenuDao.create(guildId, text.getId(), style);
		event.replyEmbeds(MessageUtil.embed("Menü angelegt",
				"ID **" + id + "** in " + text.getAsMention() + " als "
						+ (style == Menu.Style.SELECT ? "Dropdown" : "Knopfreihe")
						+ "\n\nJetzt `/menu text` zum Beschriften, `/menu hinzufuegen` für die Panels, "
						+ "dann `/menu posten`.",
				0x1ec45c)).setEphemeral(true).queue();
	}

	private void text(SlashCommandInteractionEvent event, String guildId) {
		final Optional<Menu> maybe = resolve(event, guildId);
		if (maybe.isEmpty()) {
			return;
		}
		final Menu menu = maybe.get();
		final String title = event.getOption("titel", null, OptionMapping::getAsString);
		// Discord laesst in Slash-Optionen keine echten Zeilenumbrueche zu;
		// "\n" als Text ist der uebliche Behelf.
		final String body = event.getOption("text", "", OptionMapping::getAsString).replace("\\n", "\n");
		final String placeholder = event.getOption("platzhalter", null, OptionMapping::getAsString);

		MenuDao.setEmbed(menu.id(), title, body, menu.embedColor(), placeholder);
		event.replyEmbeds(MessageUtil.embed("Gespeichert",
				"Menü " + menu.id() + " beschriftet. Mit `/menu posten` sichtbar machen.",
				0x1ec45c)).setEphemeral(true).queue();
	}

	private void addPanel(SlashCommandInteractionEvent event, String guildId) {
		final Optional<Menu> maybeMenu = resolve(event, guildId);
		if (maybeMenu.isEmpty()) {
			return;
		}
		final String panelName = event.getOption("panel", "", OptionMapping::getAsString);
		final Optional<Panel> maybePanel = PanelDao.byName(guildId, panelName);
		if (maybePanel.isEmpty()) {
			event.replyEmbeds(MessageUtil.error("Kein Panel namens `" + panelName + "`."))
					.setEphemeral(true).queue();
			return;
		}

		final Menu menu = maybeMenu.get();
		final Panel panel = maybePanel.get();
		final String label = event.getOption("label", panel.name(), OptionMapping::getAsString);
		final String emoji = event.getOption("emoji", null, OptionMapping::getAsString);
		final String description = event.getOption("beschreibung", null, OptionMapping::getAsString);
		final int position = MenuDao.entries(menu.id()).size();

		MenuDao.addEntry(menu.id(), panel.id(), label, description, emoji, 2, position);
		event.replyEmbeds(MessageUtil.embed("Hinzugefügt",
				"`" + panel.name() + "` erscheint als **" + label + "** in Menü " + menu.id()
						+ ".\nMit `/menu posten` übernehmen.",
				0x1ec45c)).setEphemeral(true).queue();
	}

	private void removePanel(SlashCommandInteractionEvent event, String guildId) {
		final Optional<Menu> maybeMenu = resolve(event, guildId);
		if (maybeMenu.isEmpty()) {
			return;
		}
		final String panelName = event.getOption("panel", "", OptionMapping::getAsString);
		final Optional<Panel> maybePanel = PanelDao.byName(guildId, panelName);
		if (maybePanel.isEmpty()) {
			event.replyEmbeds(MessageUtil.error("Kein Panel namens `" + panelName + "`."))
					.setEphemeral(true).queue();
			return;
		}
		MenuDao.removeEntry(maybeMenu.get().id(), maybePanel.get().id());
		event.replyEmbeds(MessageUtil.embed("Entfernt",
				"`" + panelName + "` wird nicht mehr angeboten. Das Panel und seine Tickets bleiben "
						+ "unangetastet.\nMit `/menu posten` übernehmen.",
				0x1ec45c)).setEphemeral(true).queue();
	}

	private void post(SlashCommandInteractionEvent event, String guildId) {
		final Optional<Menu> maybe = resolve(event, guildId);
		if (maybe.isEmpty()) {
			return;
		}
		final Menu menu = maybe.get();
		final TextChannel channel = event.getGuild().getTextChannelById(menu.channelId());
		if (channel == null) {
			event.replyEmbeds(MessageUtil.error("Der Kanal des Menüs existiert nicht mehr."))
					.setEphemeral(true).queue();
			return;
		}
		if (MenuDao.entries(menu.id()).isEmpty()) {
			event.replyEmbeds(MessageUtil.error("Das Menü hat noch kein Panel — `/menu hinzufuegen` zuerst."))
					.setEphemeral(true).queue();
			return;
		}

		MenuRenderer.postOrUpdate(channel, menu);
		event.replyEmbeds(MessageUtil.embed("Gepostet",
				"Menü " + menu.id() + " steht in " + channel.getAsMention() + ".",
				0x1ec45c)).setEphemeral(true).queue();
	}

	private void list(SlashCommandInteractionEvent event, String guildId) {
		final List<Menu> menus = MenuDao.byGuild(guildId);
		if (menus.isEmpty()) {
			event.replyEmbeds(MessageUtil.embed("Menüs", "Noch keine angelegt.", 0x1ec45c))
					.setEphemeral(true).queue();
			return;
		}
		final StringBuilder sb = new StringBuilder();
		for (final Menu m : menus) {
			sb.append("**").append(m.id()).append("** · <#").append(m.channelId()).append("> · ")
					.append(m.style() == Menu.Style.SELECT ? "Dropdown" : "Knöpfe")
					.append(m.isPosted() ? " · gepostet" : " · noch nicht gepostet")
					.append('\n');
			for (final MenuEntry e : MenuDao.entries(m.id())) {
				sb.append("   └ ").append(e.label()).append(" → ")
						.append(PanelDao.byId(e.panelId()).map(Panel::name).orElse("(Panel weg)"))
						.append('\n');
			}
		}
		event.replyEmbeds(MessageUtil.embed("Menüs (" + menus.size() + ")", sb.toString(), 0x1ec45c))
				.setEphemeral(true).queue();
	}

	private Optional<Menu> resolve(SlashCommandInteractionEvent event, String guildId) {
		final long id = event.getOption("menu", 0L, OptionMapping::getAsLong);
		final Optional<Menu> menu = MenuDao.byId(id).filter(m -> m.guildId().equals(guildId));
		if (menu.isEmpty()) {
			event.replyEmbeds(MessageUtil.error("Kein Menü mit der ID " + id + " auf diesem Server."))
					.setEphemeral(true).queue();
		}
		return menu;
	}

	@Override
	public void onCommandAutoCompleteInteraction(@Nonnull CommandAutoCompleteInteractionEvent event) {
		if (!NAME.equals(event.getName()) || event.getGuild() == null) {
			return;
		}
		final String typed = event.getFocusedOption().getValue().toLowerCase();
		if ("panel".equals(event.getFocusedOption().getName())) {
			event.replyChoices(PanelDao.byGuild(event.getGuild().getId()).stream()
					.map(Panel::name)
					.filter(n -> n.toLowerCase().contains(typed))
					.limit(25)
					.map(n -> new Command.Choice(n, n))
					.toList()).queue();
		}
	}
}
