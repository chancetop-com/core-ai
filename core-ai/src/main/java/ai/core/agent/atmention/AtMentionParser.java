package ai.core.agent.atmention;

import ai.core.agent.profile.AgentProfile;
import ai.core.agent.profile.AgentProfileRegistry;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses @agent_name prompt syntax from user input.
 * Follows the same pattern as SlashCommandParser — a framework-level protocol utility.
 *
 * @author lim chen
 */
public final class AtMentionParser {

    private static final Pattern PATTERN = Pattern.compile(
            "^@([\\w-]+)\\s+(.+)$", Pattern.DOTALL);

    public static Optional<AtMentionResult> parse(String input, AgentProfileRegistry registry) {
        if (input == null || input.isBlank()) return Optional.empty();
        Matcher m = PATTERN.matcher(input.trim());
        if (!m.matches()) return Optional.empty();

        String name = m.group(1);
        String prompt = m.group(2).trim();
        if (prompt.isEmpty()) return Optional.empty();

        AgentProfile profile = registry.get(name).orElse(null);
        if (profile == null) return Optional.empty();

        return Optional.of(new AtMentionResult(name, prompt));
    }

    public static boolean isAtMention(String query) {
        if (query == null || query.isBlank()) return false;
        return PATTERN.matcher(query.trim()).matches();
    }

    private AtMentionParser() {
    }
}
