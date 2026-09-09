package util;

import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;

/**
 * Direktnachrichten an Nutzer.
 *
 * Eine DM kann immer scheitern - viele Leute lassen keine Nachrichten von
 * Servermitgliedern zu, und das ist ihr gutes Recht. Fehlschlaege werden
 * deshalb nur vermerkt und brechen nie den Vorgang ab, der die DM ausgeloest
 * hat. Ein Ticket darf nicht daran haengen, ob sich der Bewerber anschreiben
 * laesst.
 */
public final class Dm {

	private Dm() {
	}

	public static void send(User user, MessageEmbed embed) {
		user.openPrivateChannel().queue(
				channel -> channel.sendMessageEmbeds(embed).queue(
						ok -> {
						},
						err -> System.out.println("DM an " + user.getName() + " nicht zustellbar: "
								+ err.getMessage())),
				err -> System.out.println("DM-Kanal zu " + user.getName() + " nicht zu oeffnen: "
						+ err.getMessage()));
	}
}
