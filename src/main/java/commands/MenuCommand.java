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
import util.MessageUtil;
import verwaltung.Verwaltung;

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
		final Menu.Style style = Verwaltung.stil(event.getOption("stil", "knoepfe", OptionMapping::getAsString));
		final long id = Verwaltung.menuErstellen(event.getGuild(), text.getId(), style).id();
		Verwaltung.protokoll(event.getGuild(), event.getUser().getId(), "Slash",
				"Menü " + id + " angelegt in #" + text.getName());
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

		try {
			Verwaltung.menuText(menu, title, body, placeholder, menu.embedColor());
		} catch (final Verwaltung.Fehler f) {
			event.replyEmbeds(MessageUtil.error(f.getMessage())).setEphemeral(true).queue();
			return;
		}
		Verwaltung.protokoll(event.getGuild(), event.getUser().getId(), "Slash", "Menü " + menu.id() + ": Text geändert");
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
		try {
			Verwaltung.menuEintrag(menu, panel, label, emoji, description, null, null);
		} catch (final Verwaltung.Fehler f) {
			event.replyEmbeds(MessageUtil.error(f.getMessage())).setEphemeral(true).queue();
			return;
		}
		Verwaltung.protokoll(event.getGuild(), event.getUser().getId(), "Slash",
				"Menü " + menu.id() + ": Panel „" + panel.name() + "“ als „" + label + "“ eingetragen");
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
		Verwaltung.menuEintragEntfernen(maybeMenu.get(), maybePanel.get());
		Verwaltung.protokoll(event.getGuild(), event.getUser().getId(), "Slash",
				"Menü " + maybeMenu.get().id() + ": Panel „" + panelName + "“ entfernt");
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
		final var guild = event.getGuild();
		final String userId = event.getUser().getId();
		// Posten wartet auf Discord — deshalb erst quittieren und dann im
		// eigenen Thread, erst im Callback (sonst 10062 Unknown interaction).
		event.deferReply(true).queue(hook -> new Thread(() -> {
			try {
				Verwaltung.menuPosten(guild, menu);
				Verwaltung.protokoll(guild, userId, "Slash", "Menü " + menu.id() + " gepostet/abgeglichen");
				hook.editOriginalEmbeds(MessageUtil.embed("Gepostet",
						"Menü " + menu.id() + " steht in <#" + menu.channelId() + ">.", 0x1ec45c)).queue();
			} catch (final Verwaltung.Fehler f) {
				hook.editOriginalEmbeds(MessageUtil.error(f.getMessage())).queue();
			} catch (final RuntimeException e) {
				System.err.println("Menü " + menu.id() + " konnte nicht gepostet werden: " + e);
				hook.editOriginalEmbeds(MessageUtil.error("Posten fehlgeschlagen: " + e.getMessage())).queue();
			}
		}, "menu-posten-" + menu.id()).start());
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
