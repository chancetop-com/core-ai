package ai.core.media.reference;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites author-time {@code @name} mentions into the target model's positional addressing tokens.
 * <p>
 * The agent writes semantic names ({@code @char_lin}); only names actually declared in the reference
 * list are rewritten, so an email address or a social handle in the prompt is never mangled. A name
 * whose reference was trimmed is rewritten away rather than left pointing at nothing, because after
 * a trim every later positional token would otherwise address the wrong asset.
 *
 * @author stephen
 */
public final class MediaPromptAddressing {
    private static final Pattern NAME_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    public static boolean isValidName(String name) {
        return name != null && NAME_PATTERN.matcher(name).matches();
    }

    /**
     * @param nameToToken declared name -> rendered token for references that survived compilation
     * @param droppedNames declared names whose reference was trimmed; their mentions are removed
     * @return the rewritten prompt plus the declared names that never appeared in it
     */
    public static Rewritten rewrite(String prompt, Map<String, String> nameToToken, Set<String> droppedNames) {
        if (prompt == null || prompt.isEmpty()) return new Rewritten(prompt, List.copyOf(nameToToken.keySet()));
        var unmentioned = new LinkedHashSet<>(nameToToken.keySet());
        // longest name first so "@char" never consumes the prefix of "@char_lin"
        var names = new ArrayList<String>(nameToToken.size() + droppedNames.size());
        names.addAll(nameToToken.keySet());
        names.addAll(droppedNames);
        names.sort(Comparator.comparingInt(String::length).reversed());

        var result = prompt;
        for (var name : names) {
            if (!isValidName(name)) continue;
            var replacement = nameToToken.get(name);
            var matcher = mentionPattern(name).matcher(result);
            var builder = new StringBuilder(result.length() + 32);
            var found = false;
            while (matcher.find()) {
                found = true;
                matcher.appendReplacement(builder, replacement == null ? "" : Matcher.quoteReplacement(replacement));
            }
            if (found) {
                matcher.appendTail(builder);
                result = builder.toString();
                unmentioned.remove(name);
            }
        }
        return new Rewritten(result, List.copyOf(unmentioned));
    }

    /**
     * A mention must not continue an identifier or an email local part, so {@code user@char_lin.com}
     * and {@code @char_linked} are both left alone.
     */
    private static Pattern mentionPattern(String name) {
        return Pattern.compile("(?<![A-Za-z0-9_.+-])@" + Pattern.quote(name) + "(?![A-Za-z0-9_-])");
    }

    private MediaPromptAddressing() {
    }

    public record Rewritten(String prompt, List<String> unmentionedNames) {
    }
}
