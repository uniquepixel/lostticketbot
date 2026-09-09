package panel;

import java.util.ArrayList;
import java.util.List;

import db.GuildConfigDao;
import db.MenuDao;
import db.PanelDao;
import model.GuildConfig;
import model.Menu;
import model.MenuEntry;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.ItemComponent;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.interactions.components.buttons.ButtonStyle;
import net.dv8tion.jda.api.interactions.components.selections.SelectOption;
import net.dv8tion.jda.api.interactions.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;
import util.MessageUtil;

/**
 * Baut die Menuenachricht und postet oder aktualisiert sie.
 *
 * Die custom_id traegt die Panel-ID: "tb:open:<panelId>". Damit ueberlebt ein
 * Menue jeden Neustart des Bots, ohne dass irgendwo ein Zustand im Speicher
 * gehalten werden muss - der Klick sagt selbst, was zu tun ist.
 */
public final class MenuRenderer {

	public static final String OPEN_PREFIX = "tb:open:";
	public static final String SELECT_PREFIX = "tb:menu:";

	/** Discord erlaubt hoechstens fuenf Knoepfe je Reihe. */
	private static final int BUTTONS_PER_ROW = 5;

	private MenuRenderer() {
	}

	public static void postOrUpdate(TextChannel channel, Menu menu) {
		final GuildConfig config = GuildConfigDao.get(menu.guildId());
		final int color = menu.embedColor() != null ? menu.embedColor() : config.panelEmbedColor();
		final MessageEmbed embed = MessageUtil.embed(menu.embedTitle(), menu.embedText(), color);
		final List<ActionRow> rows = buildRows(menu);

		if (menu.isPosted()) {
			// Ist die Nachricht inzwischen weg (geloescht, Kanal geleert), wird
			// einfach eine neue gepostet statt den Fehler durchzureichen.
			channel.retrieveMessageById(menu.messageId()).queue(
					existing -> existing.editMessage(new MessageEditBuilder()
							.setEmbeds(embed)
							.setComponents(rows)
							.build()).queue(),
					failure -> send(channel, menu, embed, rows));
		} else {
			send(channel, menu, embed, rows);
		}
	}

	private static void send(TextChannel channel, Menu menu, MessageEmbed embed, List<ActionRow> rows) {
		channel.sendMessage(new MessageCreateBuilder().setEmbeds(embed).setComponents(rows).build())
				.queue((Message posted) -> MenuDao.setMessageId(menu.id(), posted.getId()));
	}

	private static List<ActionRow> buildRows(Menu menu) {
		final List<MenuEntry> entries = MenuDao.entries(menu.id());
		if (entries.isEmpty()) {
			return List.of();
		}
		return menu.style() == Menu.Style.SELECT ? selectRow(menu, entries) : buttonRows(entries);
	}

	private static List<ActionRow> selectRow(Menu menu, List<MenuEntry> entries) {
		final StringSelectMenu.Builder builder = StringSelectMenu.create(SELECT_PREFIX + menu.id());
		if (menu.placeholder() != null && !menu.placeholder().isBlank()) {
			builder.setPlaceholder(menu.placeholder());
		}
		for (final MenuEntry e : entries) {
			// Ein Panel, das inzwischen stillgelegt wurde, verschwindet aus dem
			// Menue - genau der Fall Rh13/Rh14/Lost 8.
			if (!isOffered(e)) {
				continue;
			}
			SelectOption option = SelectOption.of(e.label(), Long.toString(e.panelId()));
			if (e.description() != null && !e.description().isBlank()) {
				option = option.withDescription(e.description());
			}
			if (e.emoji() != null && !e.emoji().isBlank()) {
				option = option.withEmoji(Emoji.fromFormatted(e.emoji()));
			}
			builder.addOptions(option);
		}
		return builder.getOptions().isEmpty() ? List.of() : List.of(ActionRow.of(builder.build()));
	}

	private static List<ActionRow> buttonRows(List<MenuEntry> entries) {
		final List<ItemComponent> buttons = new ArrayList<>();
		for (final MenuEntry e : entries) {
			if (!isOffered(e)) {
				continue;
			}
			Button button = Button.of(styleOf(e.buttonStyle()), OPEN_PREFIX + e.panelId(), e.label());
			if (e.emoji() != null && !e.emoji().isBlank()) {
				button = button.withEmoji(Emoji.fromFormatted(e.emoji()));
			}
			buttons.add(button);
		}

		final List<ActionRow> rows = new ArrayList<>();
		for (int i = 0; i < buttons.size(); i += BUTTONS_PER_ROW) {
			rows.add(ActionRow.of(buttons.subList(i, Math.min(i + BUTTONS_PER_ROW, buttons.size()))));
		}
		return rows;
	}

	private static boolean isOffered(MenuEntry entry) {
		return PanelDao.byId(entry.panelId()).map(model.Panel::active).orElse(false);
	}

	private static ButtonStyle styleOf(int raw) {
		return switch (raw) {
			case 1 -> ButtonStyle.PRIMARY;
			case 3 -> ButtonStyle.SUCCESS;
			case 4 -> ButtonStyle.DANGER;
			default -> ButtonStyle.SECONDARY;
		};
	}
}
