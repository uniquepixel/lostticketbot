package transcript;

import lostticketbot.Bot;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;

/**
 * Holt zu einem gespiegelten Anhang eine frische, signierte Adresse.
 *
 * Genau dafuer merkt sich die Datenbank Kanal und Nachricht statt der URL:
 * Discord signiert Anhangadressen und laesst sie nach etwa einem Tag ablaufen.
 * Die Datei liegt fest, nur ihre Adresse ist verderblich.
 *
 * Eine Stelle fuer beide Abnehmer — die HTML-Datei und das Dashboard.
 */
public final class AnhangUrl {

	private AnhangUrl() {
	}

	/** {@code null}, wenn die Nachricht nicht mehr erreichbar ist. */
	public static String frisch(String storageChannelId, String storageMessageId, String dateiname) {
		if (Bot.jda() == null || storageChannelId == null || storageMessageId == null) {
			return null;
		}
		final TextChannel storage = Bot.jda().getTextChannelById(storageChannelId);
		if (storage == null) {
			return null;
		}
		try {
			final Message m = storage.retrieveMessageById(storageMessageId).complete();
			return m.getAttachments().stream()
					.filter(att -> att.getFileName().equals(dateiname))
					.findFirst()
					.map(net.dv8tion.jda.api.entities.Message.Attachment::getUrl)
					.orElse(null);
		} catch (final RuntimeException e) {
			System.err.println("Anhang " + dateiname + " nicht abrufbar: " + e.getMessage());
			return null;
		}
	}
}
