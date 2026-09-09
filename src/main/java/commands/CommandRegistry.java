package commands;

import java.util.Arrays;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;

/**
 * Meldet die Slash-Commands an — pro Server, nicht global.
 *
 * Guild-Commands sind sofort da, globale brauchen bis zu einer Stunde. Da der
 * Bot ohnehin nur auf wenigen Servern laeuft, gibt es keinen Grund zu warten.
 */
public final class CommandRegistry {

	private CommandRegistry() {
	}

	public static void register(Guild guild) {
		final OptionData panelOption = new OptionData(OptionType.STRING, "panel", "Welches Panel", true)
				.setAutoComplete(true);

		final OptionData feldOption = new OptionData(OptionType.STRING, "feld", "Welche Einstellung", true);
		Arrays.stream(PanelCommand.Field.values())
				.forEach(f -> feldOption.addChoice(f.name().toLowerCase().replace('_', ' '), f.name()));

		guild.updateCommands().addCommands(
				Commands.slash(PanelCommand.NAME, "Ticket-Typen verwalten")
						.setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER))
						.addSubcommands(
								new SubcommandData("erstellen", "Neues Panel anlegen")
										.addOption(OptionType.STRING, "name", "Interner Name", true),
								new SubcommandData("liste", "Alle Panels dieses Servers"),
								new SubcommandData("zeigen", "Konfiguration eines Panels")
										.addOptions(panelOption),
								new SubcommandData("setzen", "Eine Einstellung ändern")
										.addOptions(panelOption)
										.addOptions(feldOption)
										.addOption(OptionType.STRING, "wert",
												"Neuer Wert, '-' löscht ihn", true),
								new SubcommandData("rolle", "Support- oder Ping-Rolle zuordnen")
										.addOptions(panelOption)
										.addOption(OptionType.ROLE, "rolle", "Welche Rolle", true)
										.addOptions(new OptionData(OptionType.STRING, "art",
												"support = Zugriff, ping = Benachrichtigung")
												.addChoice("support", "support")
												.addChoice("ping", "ping"))
										.addOption(OptionType.BOOLEAN, "entfernen",
												"Statt hinzufügen entfernen")),

				Commands.slash(MenuCommand.NAME, "Menüs verwalten, über die Tickets geöffnet werden")
						.setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER))
						.addSubcommands(
								new SubcommandData("erstellen", "Neues Menü anlegen")
										.addOption(OptionType.CHANNEL, "kanal", "Wo es stehen soll", true)
										.addOptions(new OptionData(OptionType.STRING, "stil",
												"Knopfreihe oder Dropdown")
												.addChoice("knoepfe", "knoepfe")
												.addChoice("dropdown", "dropdown")),
								new SubcommandData("text", "Titel und Text des Menüs setzen")
										.addOption(OptionType.INTEGER, "menu", "Menü-ID", true)
										.addOption(OptionType.STRING, "text",
												"Beschreibung, \\n für Zeilenumbruch", true)
										.addOption(OptionType.STRING, "titel", "Überschrift")
										.addOption(OptionType.STRING, "platzhalter",
												"Nur bei Dropdown: Text im geschlossenen Zustand"),
								new SubcommandData("hinzufuegen", "Ein Panel in dieses Menü hängen")
										.addOption(OptionType.INTEGER, "menu", "Menü-ID", true)
										.addOptions(panelOption)
										.addOption(OptionType.STRING, "label", "Beschriftung für den Nutzer")
										.addOption(OptionType.STRING, "emoji", "Emoji davor")
										.addOption(OptionType.STRING, "beschreibung",
												"Nur bei Dropdown: Zeile unter dem Label"),
								new SubcommandData("entfernen", "Ein Panel aus dem Menü nehmen")
										.addOption(OptionType.INTEGER, "menu", "Menü-ID", true)
										.addOptions(panelOption),
								new SubcommandData("posten", "Menü posten oder aktualisieren")
										.addOption(OptionType.INTEGER, "menu", "Menü-ID", true),
								new SubcommandData("liste", "Alle Menüs dieses Servers")),

				Commands.slash(LegacyCommand.NAME, "Alt-Transcripts aus Ticket Tool übernehmen")
						.setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER))
						.addSubcommands(
								new SubcommandData("importieren", "Einen Ticket-Tool-Log-Kanal einlesen")
										.addOption(OptionType.CHANNEL, "kanal",
												"Der Log-Kanal mit den Transcript-Anhängen", true),
								new SubcommandData("stand", "Wie viel ist übernommen")))
				.queue(
						ok -> System.out.println("  Befehle angemeldet für " + guild.getName()),
						err -> System.err.println("  Befehle für " + guild.getName()
								+ " konnten nicht angemeldet werden: " + err.getMessage()));
	}
}
