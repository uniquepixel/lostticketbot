package commands;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

/** /ticket info zeigt die Eröffnungszeit als Discord-Zeitstempel statt roh. */
class ZeitpunktTest {

	@Test
	void ohneZeitEinStrich() {
		assertEquals("—", TicketCommand.zeitpunkt(null));
	}

	@Test
	void augenblickInDerZoneDerJvm() {
		final LocalDateTime wann = LocalDateTime.of(2026, 11, 20, 9, 15, 17, 123_000_000);
		final long erwartet = wann.atZone(ZoneId.systemDefault()).toEpochSecond();
		assertEquals("<t:" + erwartet + ":f> (<t:" + erwartet + ":R>)", TicketCommand.zeitpunkt(wann));
	}
}
