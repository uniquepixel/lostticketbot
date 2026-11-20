package ticket;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import db.Database;
import db.PanelDao;
import db.TicketDao;
import model.Ticket;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;

/**
 * Wer darf ein Ticket wieder öffnen oder löschen ({@link Visibility#darfVerwalten}).
 *
 * Die Regel hängt am Knopf „Wieder öffnen“, an beiden Lösch-Schritten und seit
 * dem 20.11.2026 auch an {@code /ticket wiedereroeffnen}. Mitglieder sind hier
 * Attrappen (ein Bot kann sich keine Nicht-Admins herbeirufen); Panel und
 * Support-Rolle liegen in der echten Datenbank unter einer erfundenen Server-ID.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VerwaltenRechtTest {

	private static final String SUPPORT = "900000000000000001";
	private static final String FREMD = "900000000000000002";
	private static final String EROEFFNER = "900000000000000010";

	private boolean verfuegbar;
	private long panelId;
	private Ticket ticket;

	@BeforeAll
	void setUp() {
		final String dbUrl = System.getenv("TICKETBOT_DB_URL");
		verfuegbar = dbUrl != null && !dbUrl.isBlank();
		assumeTrue(verfuegbar, "TICKETBOT_DB_URL fehlt — uebersprungen");
		Database.init(dbUrl, System.getenv("TICKETBOT_DB_USER"),
				System.getenv().getOrDefault("TICKETBOT_DB_PASSWORD", ""));
		Database.applySchema();

		final String guildId = "test-recht-" + System.currentTimeMillis();
		panelId = PanelDao.create(guildId, "recht");
		PanelDao.addRole(panelId, SUPPORT, "support");
		final long id = TicketDao.create(guildId, panelId, 1, "kanal-" + guildId, "recht-0001", EROEFFNER);
		ticket = TicketDao.byId(id).orElseThrow();
	}

	@AfterAll
	void tearDown() {
		if (!verfuegbar) {
			return;
		}
		Database.update("DELETE FROM tickets WHERE id = ?", ticket.id());
		Database.update("DELETE FROM panel_roles WHERE panel_id = ?", panelId);
		Database.update("DELETE FROM panels WHERE id = ?", panelId);
		Database.shutdown();
	}

	@Test
	@DisplayName("Eröffner und Dazugeholte ohne Support-Rolle dürfen nicht verwalten")
	void ohneRolle() {
		assertFalse(Visibility.darfVerwalten(null, ticket, mitglied(List.of(), Set.of())));
		assertFalse(Visibility.darfVerwalten(null, ticket, mitglied(List.of(FREMD), Set.of())));
		assertFalse(Visibility.darfVerwalten(null, ticket, null));
	}

	@Test
	@DisplayName("Support-Rolle des Panels, Kanäle verwalten oder Server verwalten reichen")
	void mitRecht() {
		assertTrue(Visibility.darfVerwalten(null, ticket, mitglied(List.of(FREMD, SUPPORT), Set.of())));
		assertTrue(Visibility.darfVerwalten(null, ticket, mitglied(List.of(), Set.of(Permission.MANAGE_CHANNEL))));
		assertTrue(Visibility.darfVerwalten(null, ticket, mitglied(List.of(), Set.of(Permission.MANAGE_SERVER))));
	}

	/** Mitglied-Attrappe: kennt nur Rollen und serverweite Rechte. */
	private static Member mitglied(List<String> rollenIds, Set<Permission> rechte) {
		final List<Role> rollen = rollenIds.stream().map(VerwaltenRechtTest::rolle).toList();
		return (Member) Proxy.newProxyInstance(Member.class.getClassLoader(), new Class<?>[] { Member.class },
				(p, m, args) -> switch (m.getName()) {
					case "isOwner" -> false;
					case "getRoles" -> rollen;
					case "hasPermission" -> rechte.containsAll(Arrays.asList((Permission[]) args[0]));
					default -> throw new UnsupportedOperationException(m.getName());
				});
	}

	private static Role rolle(String id) {
		return (Role) Proxy.newProxyInstance(Role.class.getClassLoader(), new Class<?>[] { Role.class },
				(p, m, args) -> switch (m.getName()) {
					case "getId" -> id;
					default -> throw new UnsupportedOperationException(m.getName());
				});
	}
}
