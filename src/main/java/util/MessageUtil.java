package util;

import java.awt.Color;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;

public final class MessageUtil {

	/** Rot fuer Fehlermeldungen an den Nutzer; die Panel- und Ticketfarben kommen aus guild_config. */
	private static final Color ERROR = new Color(0xC0392B);

	private MessageUtil() {
	}

	public static MessageEmbed embed(String title, String description, int rgb) {
		final EmbedBuilder b = new EmbedBuilder().setColor(new Color(rgb));
		if (title != null && !title.isBlank()) {
			b.setTitle(title);
		}
		if (description != null && !description.isBlank()) {
			b.setDescription(description);
		}
		return b.build();
	}

	public static MessageEmbed error(String description) {
		return new EmbedBuilder().setColor(ERROR).setDescription(description).build();
	}

	/** Ersetzt die Platzhalter, die in Willkommenstexten erlaubt sind. */
	public static String fill(String template, String userMention, String panelName) {
		if (template == null) {
			return "";
		}
		return template
				.replace("{user}", userMention == null ? "" : userMention)
				.replace("{panel_name}", panelName == null ? "" : panelName);
	}
}
