package ticket;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

import db.Database;
import db.GuildConfigDao;
import db.PanelDao;
import db.TicketDao;
import model.GuildConfig;
import model.Panel;
import model.Ticket;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.managers.channel.concrete.TextChannelManager;
import net.dv8tion.jda.api.requests.restaction.ChannelAction;

/**
 * Lebenszyklus eines Tickets: öffnen, schließen, wieder öffnen.
 *
 * ACHTUNG: Die Methoden hier blockieren ({@code complete()}) und dürfen nicht
 * auf dem JDA-Event-Thread laufen. Aufrufer defern die Interaktion und arbeiten
 * in einem eigenen Thread — dasselbe Muster wie in den anderen LOST-Bots.
 */
public final class TicketService {

	/** Discords hartes Limit an Kanälen je Kategorie. */
	private static final int CHANNELS_PER_CATEGORY = 50;

	private static final EnumSet<Permission> TICKET_ACCESS = EnumSet.of(
			Permission.VIEW_CHANNEL,
			Permission.MESSAGE_SEND,
			Permission.MESSAGE_HISTORY,
			Permission.MESSAGE_ATTACH_FILES,
			Permission.MESSAGE_EMBED_LINKS,
			Permission.MESSAGE_ADD_REACTION);

	private TicketService() {
	}

	/** Ergebnis eines Öffnungsversuchs — entweder ein Ticket oder ein Grund, warum nicht. */
	public record OpenResult(Ticket ticket, TextChannel channel, String error) {
		public boolean ok() {
			return error == null;
		}

		static OpenResult fail(String reason) {
			return new OpenResult(null, null, reason);
		}
	}

	// -----------------------------------------------------------------------
	// Öffnen
	// -----------------------------------------------------------------------

	public static OpenResult open(Guild guild, Panel panel, Member opener) {
		final String guildId = guild.getId();
		final String userId = opener.getId();

		final Optional<String> blocked = checkAllowed(guild, panel, opener);
		if (blocked.isPresent()) {
			return OpenResult.fail(blocked.get());
		}

		// Zähler erst hier hochsetzen: ab hier legen wir wirklich an, und der
		// Schritt ist atomar, damit zwei gleichzeitige Klicks nicht dieselbe
		// Nummer bekommen.
		final int number = PanelDao.nextNumber(panel.id());
		final String channelName = panel.renderName(false, number, opener.getUser().getName());

		final TextChannel channel;
		try {
			channel = createChannel(guild, panel, opener, channelName);
		} catch (final RuntimeException e) {
			// Der Zähler bleibt verbraucht. Das ist Absicht: eine Lücke in der
			// Nummernfolge ist harmlos, eine doppelt vergebene Nummer nicht.
			return OpenResult.fail("Der Kanal konnte nicht angelegt werden: " + e.getMessage());
		}

		final long ticketId = TicketDao.create(guildId, panel.id(), number,
				channel.getId(), channel.getName(), userId);
		final Ticket ticket = TicketDao.byId(ticketId).orElseThrow(
				() -> new Database.DatabaseException("Ticket " + ticketId + " direkt nach dem Anlegen weg", null));

		return new OpenResult(ticket, channel, null);
	}

	/** Prüft Blacklist, Limits und Cooldown. Leeres Optional heißt: darf öffnen. */
	private static Optional<String> checkAllowed(Guild guild, Panel panel, Member opener) {
		final String guildId = guild.getId();
		final String userId = opener.getId();

		if (isBlacklisted(guildId, opener)) {
			return Optional.of("Du kannst derzeit keine Tickets öffnen.");
		}

		if (!panel.active()) {
			return Optional.of("Dieser Ticket-Typ ist derzeit nicht verfügbar.");
		}

		// Serverweites Limit zuerst: auf dem Bewerbungsserver steht es auf 1 und
		// ist damit strenger als jedes Panel-Limit.
		final GuildConfig config = GuildConfigDao.get(guildId);
		if (config.hasGlobalLimit()) {
			final int openTotal = TicketDao.countOpenByOwner(guildId, userId);
			if (openTotal >= config.globalMaxOpenTickets()) {
				return Optional.of(config.globalMaxOpenTickets() == 1
						? "Du hast bereits ein offenes Ticket. Bitte nutze es weiter, statt ein neues zu öffnen."
						: "Du hast bereits " + openTotal + " offene Tickets. Mehr sind auf diesem Server nicht möglich.");
			}
		}

		if (panel.maxOpenPerUser() > 0
				&& TicketDao.countOpenByOwnerAndPanel(panel.id(), userId) >= panel.maxOpenPerUser()) {
			return Optional.of("Du hast für diesen Bereich bereits ein offenes Ticket.");
		}

		if (panel.cooldownSeconds() > 0) {
			final Optional<LocalDateTime> last = TicketDao.lastOpenedAt(panel.id(), userId);
			if (last.isPresent()) {
				final long waited = Duration.between(last.get(), LocalDateTime.now()).getSeconds();
				if (waited < panel.cooldownSeconds()) {
					final long remaining = panel.cooldownSeconds() - waited;
					return Optional.of("Bitte warte noch " + formatDuration(remaining)
							+ ", bevor du hier ein neues Ticket öffnest.");
				}
			}
		}

		return Optional.empty();
	}

	private static boolean isBlacklisted(String guildId, Member member) {
		final List<String> subjects = new ArrayList<>();
		subjects.add(member.getId());
		member.getRoles().forEach(r -> subjects.add(r.getId()));
		for (final String subject : subjects) {
			final int hit = Database.count(
					"SELECT COUNT(*) FROM blacklist WHERE guild_id = ? AND subject_id = ?", guildId, subject);
			if (hit > 0) {
				return true;
			}
		}
		return false;
	}

	private static TextChannel createChannel(Guild guild, Panel panel, Member opener, String name) {
		final Category category = resolveCategory(guild, panel.categoryOpened());

		ChannelAction<TextChannel> action = guild.createTextChannel(name);
		if (category != null) {
			action = action.setParent(category);
		}

		// Sichtbarkeit explizit aufbauen statt sich auf die Kategorie zu
		// verlassen: ein Ticket, das versehentlich öffentlich ist, ist bei
		// Orga-Beschwerden ein echter Schaden.
		action = action.addRolePermissionOverride(guild.getPublicRole().getIdLong(),
				null, EnumSet.of(Permission.VIEW_CHANNEL));
		action = action.addMemberPermissionOverride(opener.getIdLong(), TICKET_ACCESS, null);
		action = action.addMemberPermissionOverride(guild.getSelfMember().getIdLong(),
				EnumSet.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_HISTORY,
						Permission.MESSAGE_MANAGE, Permission.MANAGE_CHANNEL),
				null);

		for (final String roleId : PanelDao.supportRoles(panel.id())) {
			final var role = guild.getRoleById(roleId);
			if (role != null) {
				action = action.addRolePermissionOverride(role.getIdLong(), TICKET_ACCESS, null);
			}
		}

		return action.reason("Ticket für " + opener.getUser().getName()).complete();
	}

	/**
	 * Sucht die Zielkategorie und weicht aus, wenn sie voll ist.
	 *
	 * Discord lässt nur 50 Kanäle je Kategorie zu. Der Bestand löst das mit
	 * durchnummerierten Kategorien ("ANGENOMMEN 1" … "ANGENOMMEN 5"), die
	 * offenbar von Hand gepflegt werden. Wir suchen deshalb bei voller
	 * Kategorie automatisch nach einer gleichnamigen mit laufender Nummer.
	 *
	 * Findet sich keine, wird der Kanal ohne Kategorie angelegt statt das
	 * Ticket scheitern zu lassen — ein falsch einsortiertes Ticket ist ein
	 * Ärgernis, ein nicht existierendes ein verlorener Bewerber.
	 */
	private static Category resolveCategory(Guild guild, String categoryId) {
		if (categoryId == null || categoryId.isBlank()) {
			return null;
		}
		final Category configured = guild.getCategoryById(categoryId);
		if (configured == null) {
			System.err.println("Kategorie " + categoryId + " existiert nicht mehr — Ticket landet ohne Kategorie.");
			return null;
		}
		if (configured.getChannels().size() < CHANNELS_PER_CATEGORY) {
			return configured;
		}

		final String base = stripTrailingNumber(configured.getName());
		for (final Category candidate : guild.getCategories()) {
			if (candidate.getIdLong() == configured.getIdLong()) {
				continue;
			}
			if (stripTrailingNumber(candidate.getName()).equalsIgnoreCase(base)
					&& candidate.getChannels().size() < CHANNELS_PER_CATEGORY) {
				System.out.println("Kategorie '" + configured.getName() + "' ist voll, weiche aus auf '"
						+ candidate.getName() + "'.");
				return candidate;
			}
		}

		System.err.println("Kategorie '" + configured.getName()
				+ "' ist voll und es gibt keine freie Folgekategorie — Ticket landet ohne Kategorie.");
		return null;
	}

	/** "ANGENOMMEN 3" -> "ANGENOMMEN", damit Folgekategorien erkannt werden. */
	static String stripTrailingNumber(String name) {
		return name == null ? "" : name.replaceAll("\\s*\\d+\\s*$", "").trim();
	}

	// -----------------------------------------------------------------------
	// Schließen und wieder öffnen
	// -----------------------------------------------------------------------

	public static void close(Guild guild, Ticket ticket, Panel panel, String closedBy, String reason) {
		final TextChannel channel = guild.getTextChannelById(ticket.channelId());
		final String newName = panel.renderName(true, ticket.number(), null);

		if (channel != null) {
			TextChannelManager manager = channel.getManager().setName(newName);

			final Category closedCategory = resolveCategory(guild, panel.categoryClosed());
			if (closedCategory != null) {
				manager = manager.setParent(closedCategory);
			}
			manager.reason("Ticket geschlossen").complete();

			// Der Eröffner verliert das Schreibrecht, behält aber Lesezugriff —
			// er soll nachlesen können, was besprochen wurde.
			channel.upsertPermissionOverride(guild.retrieveMemberById(ticket.ownerId()).complete())
					.grant(Permission.VIEW_CHANNEL, Permission.MESSAGE_HISTORY)
					.deny(Permission.MESSAGE_SEND)
					.reason("Ticket geschlossen")
					.complete();
		}

		TicketDao.close(ticket.id(), closedBy, reason, newName);
	}

	public static void reopen(Guild guild, Ticket ticket, Panel panel) {
		final TextChannel channel = guild.getTextChannelById(ticket.channelId());
		final String newName = panel.renderName(false, ticket.number(), null);

		if (channel != null) {
			TextChannelManager manager = channel.getManager().setName(newName);
			final Category openCategory = resolveCategory(guild, panel.categoryOpened());
			if (openCategory != null) {
				manager = manager.setParent(openCategory);
			}
			manager.reason("Ticket wieder geöffnet").complete();

			channel.upsertPermissionOverride(guild.retrieveMemberById(ticket.ownerId()).complete())
					.grant(TICKET_ACCESS)
					.reason("Ticket wieder geöffnet")
					.complete();
		}

		TicketDao.reopen(ticket.id(), newName);
	}

	private static String formatDuration(long seconds) {
		if (seconds < 60) {
			return seconds + " Sekunden";
		}
		final long minutes = seconds / 60;
		if (minutes < 60) {
			return minutes + " Minuten";
		}
		return (minutes / 60) + " Stunden";
	}
}
