package ticket;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import db.PanelDao;
import model.Panel;
import model.Ticket;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;

/**
 * Wer darf welche Tickets lesen.
 *
 * Eine Stelle fuer beide Zugaenge — den {@code /transcript}-Befehl in Discord
 * und die Dashboard-API der Website. Zwei getrennte Implementierungen waeren
 * genau die Art von Doppelung, bei der die eine irgendwann grosszuegiger wird
 * als die andere, ohne dass es jemandem auffaellt.
 *
 * Die Regel ist bewusst simpel: dasselbe wie in Discord. Wer die Support-Rolle
 * eines Panels traegt, darf dessen Tickets lesen; wer den Server verwalten
 * darf, alles; und jeder sein eigenes.
 *
 * Das ist nicht nur bequem, sondern noetig. In den Orga-Tickets auf LOST Family
 * beschweren sich Mitglieder ueber namentlich genannte Anfuehrer. Haenge man
 * die Sichtbarkeit an einer Rangfolge auf, koennte jeder Anfuehrer nachlesen,
 * wer sich ueber ihn beschwert hat.
 */
public final class Visibility {

	private Visibility() {
	}

	public static List<Panel> sichtbarePanels(Guild guild, String discordUserId) {
		final Member member = mitglied(guild, discordUserId);
		if (member == null) {
			return List.of();
		}
		final List<Panel> alle = PanelDao.byGuild(guild.getId());
		if (darfAllesSehen(member)) {
			return alle;
		}

		final Set<String> eigeneRollen = new HashSet<>();
		member.getRoles().forEach(r -> eigeneRollen.add(r.getId()));

		final List<Panel> sichtbar = new ArrayList<>();
		for (final Panel panel : alle) {
			if (PanelDao.supportRoles(panel.id()).stream().anyMatch(eigeneRollen::contains)) {
				sichtbar.add(panel);
			}
		}
		return sichtbar;
	}

	public static boolean darfSehen(Guild guild, Ticket ticket, String discordUserId) {
		if (ticket.ownerId().equals(discordUserId)) {
			return true;   // das eigene Ticket immer
		}
		if (ticket.panelId() == null) {
			// Ohne Panel gibt es keine Support-Rolle, an der man es festmachen
			// koennte — dann entscheidet die Serververwaltung.
			final Member member = mitglied(guild, discordUserId);
			return member != null && darfAllesSehen(member);
		}
		return sichtbarePanels(guild, discordUserId).stream()
				.anyMatch(p -> p.id() == ticket.panelId());
	}

	public static boolean darfAllesSehen(Member member) {
		return member.isOwner()
				|| member.hasPermission(Permission.ADMINISTRATOR)
				|| member.hasPermission(Permission.MANAGE_SERVER);
	}

	public static Member mitglied(Guild guild, String userId) {
		try {
			final Member ausCache = guild.getMemberById(userId);
			return ausCache != null ? ausCache : guild.retrieveMemberById(userId).complete();
		} catch (final RuntimeException e) {
			return null;
		}
	}
}
