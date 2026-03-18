package ai.core.cli.hook;

public record HookEntry(String command, String matcher) {
    public boolean matches(String target) {
        if (matcher == null || matcher.isEmpty()) return true;
        return target != null && target.contains(matcher);
    }
}
