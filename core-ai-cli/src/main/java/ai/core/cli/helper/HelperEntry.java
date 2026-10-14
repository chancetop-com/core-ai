package ai.core.cli.helper;

/**
 * One configured helper: a server agent mounted as a delegation tool of the local main agent.
 * {@code name}/{@code description} are a snapshot taken when the helper was added so the tool
 * stays describable while the server is unreachable.
 *
 * @author stephen
 */
public class HelperEntry {
    /** A missing flag means enabled; only an explicit {@code false} pauses the helper. */
    public static boolean enabled(HelperEntry entry) {
        return entry.enabled == null || entry.enabled;
    }

    public static String displayName(HelperEntry entry) {
        return entry.name != null && !entry.name.isBlank() ? entry.name : entry.agentId;
    }

    public String agentId;
    public String name;
    public String description;
    public String whenToUse;
    public Boolean enabled;
    public String addedAt;
}
