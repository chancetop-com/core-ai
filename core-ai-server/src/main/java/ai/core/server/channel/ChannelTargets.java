package ai.core.server.channel;

/**
 * Which inbound addresses are fit to be a personal notification target. A session-completion notice
 * is personal, so a shared place — a QQ group, a guild — must never be remembered as the user's
 * address: the notice would land in front of everyone. Targets without an explicit kind segment do
 * not declare one, so they pass through unchanged.
 *
 * @author stephen
 */
public final class ChannelTargets {
    private static final String GROUP = "group";
    private static final String GUILD = "guild";

    /** The target to remember, or null when it addresses a shared place rather than this user. */
    public static String directTarget(String target) {
        if (target == null || target.isBlank()) return null;
        var parts = target.split(":", 3);
        if (parts.length >= 3) {
            var kind = parts[1];
            if (GROUP.equalsIgnoreCase(kind) || GUILD.equalsIgnoreCase(kind)) return null;
        }
        return target;
    }

    private ChannelTargets() {
    }
}
