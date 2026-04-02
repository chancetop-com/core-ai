package ai.core.cli.ui;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stores large paste contents behind a placeholder token.
 * Placeholders are expanded back to full content before submission.
 */
public class PasteBuffer {
    private static final int CHAR_THRESHOLD = 1000;
    private static final int LINE_THRESHOLD = 10;
    private static final Pattern PLACEHOLDER =
            Pattern.compile("\\[Pasted \\d+ chars, \\d+ lines #(\\d+)]");

    private final Map<String, String> contents = new ConcurrentHashMap<>();
    private final AtomicInteger idGen = new AtomicInteger();

    public boolean isLarge(String text) {
        if (text.length() > CHAR_THRESHOLD) return true;
        return text.chars().filter(c -> c == '\n').count() >= LINE_THRESHOLD;
    }

    public String store(String text) {
        String id = String.valueOf(idGen.incrementAndGet());
        contents.put(id, text);
        int lines = (int) text.chars().filter(c -> c == '\n').count() + 1;
        return "[Pasted " + text.length() + " chars, " + lines + " lines #" + id + "]";
    }

    public String expand(String input) {
        Matcher m = PLACEHOLDER.matcher(input);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String actual = contents.remove(m.group(1));
            m.appendReplacement(sb, actual != null ? Matcher.quoteReplacement(actual) : m.group());
        }
        m.appendTail(sb);
        return sb.toString();
    }
}