package ai.core.llm;

import ai.core.llm.providers.LLMContextWindowExceededException;

import java.util.regex.Pattern;

/**
 * Recognizes provider rejections caused by an input that exceeds the model's context window, and
 * extracts the declared limit when the provider includes one in its error message.
 *
 * @author stephen
 */
public final class ContextWindowErrors {
    private static final Pattern OVERFLOW = Pattern.compile(
            "context[ _-]?length"
                    + "|maximum context"
                    + "|context window"
                    + "|prompt is too long"
                    + "|input is too long"
                    + "|too many tokens"
                    + "|exceeds? the maximum number of tokens"
                    + "|reduce the length of the messages",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern DECLARED_LIMIT = Pattern.compile(
            "(?:maximum (?:context length|number of tokens)|maximum context length|context (?:length|window)"
                    + "|max(?:imum)? (?:context|input) tokens?|limit)"
                    + "[^0-9]{0,40}([0-9][0-9,._]*)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern TRAILING_LIMIT = Pattern.compile(
            "([0-9][0-9,._]*)\\s*(?:tokens?\\s*)?(?:maximum|max)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern NUMBER = Pattern.compile("[0-9][0-9,._]*");

    private static final int MAX_DECLARED_LIMIT = 10_000_000;

    public static boolean matches(String text) {
        return text != null && OVERFLOW.matcher(text).find();
    }

    /** True only when the provider's own message (or its typed exception) confirms a context-length rejection. */
    public static boolean isContextWindowExceeded(Throwable error) {
        int depth = 0;
        for (Throwable cause = error; cause != null && depth < 10; cause = cause.getCause(), depth++) {
            if (cause instanceof LLMContextWindowExceededException) {
                return true;
            }
            if (matches(cause.getMessage())) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return the input token limit declared by the provider, or 0 when the message carries none
     */
    public static int declaredLimit(String text) {
        if (text == null) {
            return 0;
        }
        var matcher = DECLARED_LIMIT.matcher(text);
        if (matcher.find()) {
            return parse(matcher.group(1));
        }
        matcher = TRAILING_LIMIT.matcher(text);
        if (matcher.find()) {
            return parse(matcher.group(1));
        }
        int smallest = Integer.MAX_VALUE;
        matcher = NUMBER.matcher(text);
        while (matcher.find()) {
            int value = parse(matcher.group());
            if (value >= 1000 && value < smallest) {
                smallest = value;
            }
        }
        return smallest == Integer.MAX_VALUE ? 0 : smallest;
    }

    private static int parse(String raw) {
        var cleaned = raw.replace(",", "").replace("_", "").replace(".", "");
        try {
            long value = Long.parseLong(cleaned);
            return value > 0 && value <= MAX_DECLARED_LIMIT ? (int) value : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private ContextWindowErrors() {
    }
}
