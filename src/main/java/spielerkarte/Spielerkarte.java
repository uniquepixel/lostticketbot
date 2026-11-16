package spielerkarte;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;

/**
 * Baut aus einem CoC-Spielerprofil die Karte, die im Bewerbungsticket erscheint.
 *
 * Anlass (Eigeninitiative 16.11.2026): In fast jedem Bewerbungsticket fragt das
 * Team zuerst nach Screenshots von Base, Profil, Helden und Ausrüstung. Drei
 * davon stehen vollständig in der API; nur das Base-Layout nicht.
 */
public final class Spielerkarte {

	static final int FELD_MAX = MessageEmbed.VALUE_MAX_LENGTH; // 1024
	private static final NumberFormat ZAHL = NumberFormat.getIntegerInstance(Locale.GERMANY);

	private Spielerkarte() {
	}

	public static MessageEmbed bauen(JsonNode p, int farbe) {
		final String tag = p.path("tag").asText("").replace("#", "");
		final EmbedBuilder e = new EmbedBuilder()
				.setTitle("Spielerkarte: " + p.path("name").asText("?") + " (#" + tag + ")",
						"https://link.clashofclans.com/de?action=OpenPlayerProfile&tag=" + tag)
				.setColor(farbe);

		final StringBuilder rh = new StringBuilder("Rathaus **" + p.path("townHallLevel").asInt() + "**");
		if (p.hasNonNull("townHallWeaponLevel")) {
			rh.append(" (Waffe ").append(p.path("townHallWeaponLevel").asInt()).append(')');
		}
		rh.append(" · Erfahrung ").append(p.path("expLevel").asInt());
		e.addField("Account", rh.toString(), false);

		final JsonNode clan = p.path("clan");
		final String clanText = clan.isMissingNode() ? "kein Clan"
				: clan.path("name").asText("?") + " (" + rolle(p.path("role").asText("")) + ")";
		e.addField("Clan", clanText + " · Kriegsbereit: "
				+ ("in".equals(p.path("warPreference").asText()) ? "ja" : "nein"), false);

		final String liga = p.path("leagueTier").path("name").asText(p.path("league").path("name").asText("–"));
		e.addField("Liga", liga + " · Rekord " + zahl(p.path("bestTrophies").asLong()) + " Trophäen", false);

		e.addField("Krieg", "Kriegssterne " + zahl(p.path("warStars").asLong())
				+ " · CWL-Sterne gesamt " + zahl(erfolg(p, "War League Legend")), false);
		e.addField("Spenden", "diese Saison " + zahl(p.path("donations").asLong())
				+ " · insgesamt " + zahl(erfolg(p, "Friend in Need")), false);

		e.addField("Helden", liste(p.path("heroes"), true), false);
		e.addField("Ausrüstung", liste(p.path("heroEquipment"), true), false);
		e.setFooter("Aus der CoC-API beim Posten des Tags · Base-Layout bitte weiter als Screenshot");
		return e.build();
	}

	/** Stufen des Hauptdorfs, höchste zuerst, gekürzt auf ein Embed-Feld. */
	static String liste(JsonNode eintraege, boolean nurHauptdorf) {
		final List<JsonNode> l = new ArrayList<>();
		eintraege.forEach(n -> {
			if (!nurHauptdorf || "home".equals(n.path("village").asText("home"))) {
				l.add(n);
			}
		});
		if (l.isEmpty()) {
			return "–";
		}
		l.sort(Comparator.comparingInt((JsonNode n) -> n.path("level").asInt()).reversed());
		final StringBuilder sb = new StringBuilder();
		for (int i = 0; i < l.size(); i++) {
			final String teil = (sb.length() == 0 ? "" : " · ") + l.get(i).path("name").asText("?") + " "
					+ l.get(i).path("level").asInt();
			final String rest = " · +" + (l.size() - i) + " weitere";
			if (sb.length() + teil.length() + rest.length() > FELD_MAX) {
				sb.append(rest);
				break;
			}
			sb.append(teil);
		}
		return sb.toString();
	}

	static long erfolg(JsonNode p, String name) {
		for (final JsonNode a : p.path("achievements")) {
			if (name.equals(a.path("name").asText())) {
				return a.path("value").asLong();
			}
		}
		return 0;
	}

	static String rolle(String api) {
		return switch (api) {
			case "leader" -> "Anführer";
			case "coLeader" -> "Vize";
			case "admin" -> "Ältester";
			case "member" -> "Mitglied";
			default -> api.isEmpty() ? "?" : api;
		};
	}

	private static String zahl(long n) {
		synchronized (ZAHL) {
			return ZAHL.format(n);
		}
	}
}
