package lostticketbot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.dv8tion.jda.api.entities.Message.MentionType;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageRequest;

/**
 * Was der Bot schreibt, pingt niemanden — ausser dort, wo es ausdruecklich
 * gewollt ist.
 *
 * Anlass: der Storage-Kanal. Dort steht in jedem Archiv-Post die Owner-ID im
 * Klartext, damit der Kanal ohne Datenbank durchsuchbar bleibt — und schickte
 * damit bei jedem geschlossenen Ticket eine Benachrichtigung an den Eroeffner.
 * Auch der Log-Eintrag und die Schliessnachricht nennen Leute beim Namen.
 *
 * Die Loesung ist eine stille Voreinstellung statt einer Aufraeumaktion an
 * jeder Fundstelle: so pingt auch die naechste Nachricht nicht, die jemand
 * hinzufuegt, ohne daran zu denken.
 */
class ErwaehnungenTest {

	@Test
	@DisplayName("Voreinstellung: keine Erwähnung löst eine Benachrichtigung aus")
	void standardIstStumm() {
		Bot.erwaehnungenStandardmaessigStumm();
		assertTrue(MessageRequest.getDefaultMentions().isEmpty(),
				"Sonst pingt jeder Archiv-Post den Eröffner");
	}

	@Test
	@DisplayName("Die Willkommensnachricht darf ausdrücklich pingen")
	void willkommenPingtWeiterhin() {
		Bot.erwaehnungenStandardmaessigStumm();

		// Genau das tut postWelcome: die stille Voreinstellung fuer diese eine
		// Nachricht ausdruecklich aufheben.
		final MessageCreateBuilder b = new MessageCreateBuilder()
				.setAllowedMentions(EnumSet.of(MentionType.USER, MentionType.ROLE))
				.setContent("<@1> <@&2> Hey");

		assertEquals(EnumSet.of(MentionType.USER, MentionType.ROLE),
				EnumSet.copyOf(b.getAllowedMentions()),
				"Eröffner und zuständige Rolle sollen es mitbekommen");
	}

	@Test
	@DisplayName("Ohne ausdrückliche Ausnahme bleibt eine Nachricht stumm")
	void ohneAusnahmeStumm() {
		Bot.erwaehnungenStandardmaessigStumm();
		final MessageCreateBuilder b = new MessageCreateBuilder()
				.setContent("**ticket-0001** · Owner <@326398089298444290> · 9 Nachrichten");
		assertTrue(b.getAllowedMentions().isEmpty(),
				"Der Archiv-Kopf im Storage-Kanal darf niemanden benachrichtigen");
	}
}
