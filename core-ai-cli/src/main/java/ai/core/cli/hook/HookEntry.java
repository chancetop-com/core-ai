package ai.core.cli.hook;

/**
 * Hook entry with optional plugin name for deduplication.
 * Deduplication key: plugin + command
 */
public record HookEntry(String plugin, String command, String matcher) {
    
    public static HookEntry of(String command, String matcher) {
        return new HookEntry(null, command, matcher);
    }
    
    public static HookEntry ofPlugin(String plugin, String command, String matcher) {
        return new HookEntry(plugin, command, matcher);
    }
    
    /**
     * Deduplication key for this hook.
     * If plugin is null, uses command only (hooks.json style).
     */
    public String dedupeKey() {
        return plugin != null ? plugin + ":" + command : command;
    }
    
    public boolean matches(String target) {
        if (matcher == null || matcher.isEmpty()) return true;
        return target != null && target.contains(matcher);
    }
}
