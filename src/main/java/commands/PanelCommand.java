package commands;

import java.util.List;
import java.util.Optional;

import javax.annotation.Nonnull;

import db.PanelDao;
import model.Panel;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import util.MessageUtil;

/**
 * Verwaltung der Panels — also der Ticket-Typen.
 *
 * Ein Panel traegt seine gesamte Konfiguration und einen eigenen Zaehler. Ueber
 * ein {@code /menu} wird es den Nutzern angeboten; ohne Menue existiert es
 * weiter, ist aber nicht erreichbar. Genau so legt man ein Panel still, ohne
 * seine Alt-Tickets zu verwaisen.
 */
public class PanelCommand extends ListenerAdapter {

	public static final String NAME = "panel";

	/** Felder, die {@code /panel set} aendern darf, mit ihrer Spalte und ihrem Typ. */
	public enum Field {
		KATEGORIE_OFFEN("category_opened", Kind.TEXT),
		KATEGORIE_GESCHLOSSEN("category_closed", Kind.TEXT),
		NAME_OFFEN("name_pattern_open", Kind.TEXT),
		NAME_GESCHLOSSEN("name_pattern_closed", Kind.TEXT),
		ZAEHLER("counter", Kind.NUMBER),
		STELLEN("counter_padding", Kind.NUMBER),
		WILLKOMMENSTEXT("welcome_text", Kind.TEXT),
		EMBED_TITEL("welcome_embed_title", Kind.TEXT),
		EMBED_TEXT("welcome_embed_text", Kind.TEXT),
		LOG_KANAL("log_channel_id", Kind.TEXT),
		MAX_OFFEN("max_open_per_user", Kind.NUMBER),
		COOLDOWN("cooldown_seconds", Kind.NUMBER),
		SCHLIESSEN_WENN_WEG("close_on_owner_leave", Kind.BOOL),
		WEGGEGANGEN_TEXT("owner_leave_message", Kind.TEXT),
		DM_BEIM_OEFFNEN("dm_on_open", Kind.BOOL),
		DM_BEIM_SCHLIESSEN("dm_on_close", Kind.BOOL),
		AKTIV("active", Kind.BOOL);

		public enum Kind {
			TEXT, NUMBER, BOOL
		}

		public final String column;
		public final Kind kind;

		Field(String column, Kind kind) {
			this.column = column;
			this.kind = kind;
		}

		static Optional<Field> of(String raw) {
			for (final Field f : values()) {
				if (f.name().equalsIgnoreCase(raw)) {
					return Optional.of(f);
				}
			}
			return Optional.empty();
		}
	}

	@Override
	public void onSlashCommandInteraction(@Nonnull SlashCommandInteractionEvent event) {
		if (!NAME.equals(event.getName()) || event.getGuild() == null) {
			return;
		}
		final String guildId = event.getGuild().getId();

		switch (String.valueOf(event.getSubcommandName())) {
			case "erstellen" -> create(event, guildId);
			case "liste" -> list(event, guildId);
			case "zeigen" -> show(event, guildId);
			case "setzen" -> set(event, guildId);
			case "rolle" -> role(event, guildId);
			default -> event.reply("Unbekannter Unterbefehl.").setEphemeral(true).queue();
		}
	}

	private void create(SlashCommandInteractionEvent event, String guildId) {
		final String name = event.getOption("name", "", OptionMapping::getAsString).trim();
		if (name.isEmpty()) {
			event.replyEmbeds(MessageUtil.error("Der Name darf nicht leer sein.")).setEphemeral(true).queue();
			return;
		}
		if (PanelDao.byName(guildId, name).isPresent()) {
			event.replyEmbeds(MessageUtil.error("Ein Panel namens `" + name + "` gibt es schon."))
					.setEphemeral(true).queue();
			return;
		}
		final long id = PanelDao.create(guildId, name);
		event.replyEmbeds(MessageUtil.embed("Panel angelegt",
				"`" + name + "` (ID " + id + ")\n\nJetzt mit `/panel setzen` konfigurieren und mit "
						+ "`/menu hinzufuegen` in ein Menü hängen.",
				0x1ec45c)).setEphemeral(true).queue();
	}

	private void list(SlashCommandInteractionEvent event, String guildId) {
		final List<Panel> panels = PanelDao.byGuild(guildId);
		if (panels.isEmpty()) {
			event.replyEmbeds(MessageUtil.embed("Panels", "Noch keine angelegt.", 0x1ec45c))
					.setEphemeral(true).queue();
			return;
		}
		final StringBuilder sb = new StringBuilder();
		for (final Panel p : panels) {
			sb.append(p.active() ? "🟢 " : "⚪ ")
					.append("`").append(p.name()).append("`")
					.append(" · Zähler ").append(p.counter())
					.append(" · `").append(p.renderName(false, p.counter() + 1, "name")).append("`")
					.append('\n');
		}
		event.replyEmbeds(MessageUtil.embed("Panels (" + panels.size() + ")", sb.toString(), 0x1ec45c))
				.setEphemeral(true).queue();
	}

	private void show(SlashCommandInteractionEvent event, String guildId) {
		final Optional<Panel> maybe = resolve(event, guildId);
		if (maybe.isEmpty()) {
			return;
		}
		final Panel p = maybe.get();
		final String text = """
				**Aktiv:** %s
				**Kategorie offen:** %s
				**Kategorie geschlossen:** %s
				**Namensmuster:** `%s` / `%s`
				**Zähler:** %d (%d Stellen) → nächstes: `%s`
				**Log-Kanal:** %s
				**Limit pro Nutzer:** %d · **Cooldown:** %ds
				**Schließen wenn Eröffner geht:** %s
				**Support-Rollen:** %s
				**Ping-Rollen:** %s"""
				.formatted(
						p.active() ? "ja" : "nein",
						orDash(p.categoryOpened()), orDash(p.categoryClosed()),
						p.namePatternOpen(), p.namePatternClosed(),
						p.counter(), p.counterPadding(), p.renderName(false, p.counter() + 1, "name"),
						p.logChannelId() == null ? "—" : "<#" + p.logChannelId() + ">",
						p.maxOpenPerUser(), p.cooldownSeconds(),
						p.closeOnOwnerLeave() ? "ja" : "nein",
						mentionRoles(PanelDao.supportRoles(p.id())),
						mentionRoles(PanelDao.pingRoles(p.id())));
		event.replyEmbeds(MessageUtil.embed("Panel " + p.name(), text, 0x1ec45c)).setEphemeral(true).queue();
	}

	private void set(SlashCommandInteractionEvent event, String guildId) {
		final Optional<Panel> maybe = resolve(event, guildId);
		if (maybe.isEmpty()) {
			return;
		}
		final Panel panel = maybe.get();

		final Optional<Field> field = Field.of(event.getOption("feld", "", OptionMapping::getAsString));
		if (field.isEmpty()) {
			event.replyEmbeds(MessageUtil.error("Unbekanntes Feld.")).setEphemeral(true).queue();
			return;
		}
		final String raw = event.getOption("wert", "", OptionMapping::getAsString).trim();

		final Object value;
		try {
			value = switch (field.get().kind) {
				case NUMBER -> Integer.parseInt(raw);
				case BOOL -> parseBool(raw);
				case TEXT -> raw.isEmpty() || "-".equals(raw) ? null : raw;
			};
		} catch (final IllegalArgumentException e) {
			event.replyEmbeds(MessageUtil.error(
					"`" + raw + "` passt nicht zum Feld " + field.get().name().toLowerCase()
							+ ". Erwartet: " + switch (field.get().kind) {
								case NUMBER -> "eine Zahl";
								case BOOL -> "ja oder nein";
								case TEXT -> "Text";
							}))
					.setEphemeral(true).queue();
			return;
		}

		PanelDao.set(panel.id(), field.get().column, value);
		event.replyEmbeds(MessageUtil.embed("Gespeichert",
				"`" + panel.name() + "` · " + field.get().name().toLowerCase() + " = `"
						+ (value == null ? "—" : value) + "`",
				0x1ec45c)).setEphemeral(true).queue();
	}

	private void role(SlashCommandInteractionEvent event, String guildId) {
		final Optional<Panel> maybe = resolve(event, guildId);
		if (maybe.isEmpty()) {
			return;
		}
		final Panel panel = maybe.get();
		final var role = event.getOption("rolle").getAsRole();
		final String kind = event.getOption("art", "support", OptionMapping::getAsString);
		final boolean remove = event.getOption("entfernen", false, OptionMapping::getAsBoolean);

		if (remove) {
			PanelDao.removeRole(panel.id(), role.getId(), kind);
		} else {
			PanelDao.addRole(panel.id(), role.getId(), kind);
		}
		event.replyEmbeds(MessageUtil.embed("Gespeichert",
				role.getAsMention() + (remove ? " entfernt von " : " hinzugefügt zu ") + "`" + panel.name()
						+ "` (" + kind + ")",
				0x1ec45c)).setEphemeral(true).queue();
	}

	// -----------------------------------------------------------------------

	private Optional<Panel> resolve(SlashCommandInteractionEvent event, String guildId) {
		final String name = event.getOption("panel", "", OptionMapping::getAsString);
		final Optional<Panel> panel = PanelDao.byName(guildId, name);
		if (panel.isEmpty()) {
			event.replyEmbeds(MessageUtil.error("Kein Panel namens `" + name + "`."))
					.setEphemeral(true).queue();
		}
		return panel;
	}

	private static boolean parseBool(String raw) {
		return switch (raw.toLowerCase()) {
			case "ja", "true", "an", "1" -> true;
			case "nein", "false", "aus", "0" -> false;
			default -> throw new IllegalArgumentException(raw);
		};
	}

	private static String orDash(String value) {
		return value == null || value.isBlank() ? "—" : "<#" + value + ">";
	}

	private static String mentionRoles(List<String> ids) {
		return ids.isEmpty() ? "—" : String.join(", ", ids.stream().map(i -> "<@&" + i + ">").toList());
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
