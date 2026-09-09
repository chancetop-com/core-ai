package ai.core.server.project;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/**
 * Name normalization of the subject auto-discovery dedup: two names that differ only by case,
 * spacing, punctuation, {@code &}/{@code and} or a trailing generic suffix are the SAME subject.
 * Exact matching only — fuzzy variants are left to the attributor prompt and the manual merge.
 *
 * @author stephen
 */
public final class ProjectSubjectNames {
    private static final List<String> GENERIC_SUFFIXES = List.of("inc", "llc", "ltd", "co", "spa", "restaurant", "store");

    public static String normalize(String name) {
        if (name == null) return null;
        var text = Normalizer.normalize(name, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        text = text.replace("&", " and ");
        text = text.replaceAll("[\\p{P}\\p{S}]", " ");
        text = text.replaceAll("\\s+", " ").trim();
        return stripGenericSuffixes(text);
    }

    // "H Spa Inc" and "H" are the same subject: drop trailing generic words until none is left
    private static String stripGenericSuffixes(String text) {
        var result = text;
        var stripped = true;
        while (stripped) {
            stripped = false;
            for (var suffix : GENERIC_SUFFIXES) {
                var tail = " " + suffix;
                if (result.endsWith(tail) && result.length() > tail.length()) {
                    result = result.substring(0, result.length() - tail.length()).trim();
                    stripped = true;
                }
            }
        }
        return result;
    }

    private ProjectSubjectNames() {
    }
}
