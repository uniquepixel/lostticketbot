package transcript;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.UncheckedIOException;
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Ein einzelner Netzaussetzer darf keinen Anhang mehr kosten.
 *
 * Bis November 2026 gab es genau einen Versuch je Anhang; drei Screenshots
 * gingen so an einem Timeout bzw. Stream-Reset verloren.
 */
class AnhangWiederholungTest {

	private static final long[] OHNE_PAUSE = { 0, 0 };

	@Test
	@DisplayName("Aussetzer beim ersten Versuch: zweiter Versuch liefert")
	void zweiterVersuchRettet() throws Exception {
		final AtomicInteger aufrufe = new AtomicInteger();
		final String ergebnis = TranscriptRecorder.mitWiederholung("Probe", OHNE_PAUSE, () -> {
			if (aufrufe.incrementAndGet() == 1) {
				throw new UncheckedIOException(new SocketTimeoutException("timeout"));
			}
			return "gespiegelt";
		});
		assertEquals("gespiegelt", ergebnis);
		assertEquals(2, aufrufe.get());
	}

	@Test
	@DisplayName("Erfolg sofort: genau ein Versuch")
	void keinUnnoetigerVersuch() throws Exception {
		final AtomicInteger aufrufe = new AtomicInteger();
		TranscriptRecorder.mitWiederholung("Probe", OHNE_PAUSE, () -> aufrufe.incrementAndGet());
		assertEquals(1, aufrufe.get());
	}

	@Test
	@DisplayName("Dauerhaft kaputt: nach drei Versuchen kommt der letzte Fehler")
	void gibtNachDreiVersuchenAuf() {
		final AtomicInteger aufrufe = new AtomicInteger();
		final IllegalStateException letzter = new IllegalStateException("drei");
		final Exception e = assertThrows(IllegalStateException.class,
				() -> TranscriptRecorder.mitWiederholung("Probe", OHNE_PAUSE, () -> {
					if (aufrufe.incrementAndGet() == 3) {
						throw letzter;
					}
					throw new IllegalStateException("frueh");
				}));
		assertSame(letzter, e);
		assertEquals(3, aufrufe.get());
	}

	@Test
	@DisplayName("Ein Error wird nicht wiederholt")
	void errorGehtDurch() {
		final AtomicInteger aufrufe = new AtomicInteger();
		assertThrows(OutOfMemoryError.class,
				() -> TranscriptRecorder.mitWiederholung("Probe", OHNE_PAUSE, () -> {
					aufrufe.incrementAndGet();
					throw new OutOfMemoryError("probe");
				}));
		assertEquals(1, aufrufe.get());
	}

	@Test
	@DisplayName("Echte Vorgabe: zwei Pausen, also drei Versuche")
	void vorgabe() {
		assertEquals(2, TranscriptRecorder.WIEDERHOLUNG_PAUSEN_MS.length);
	}
}
