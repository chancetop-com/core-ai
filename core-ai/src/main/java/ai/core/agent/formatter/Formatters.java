package ai.core.agent.formatter;

import ai.core.agent.formatter.formatters.DefaultCodeFormatter;
import ai.core.agent.formatter.formatters.DefaultJsonFormatter;

import java.util.Locale;

/**
 * @author stephen
 */
public class Formatters {
    public static Formatter getFormatter(String name) {
        var type = FormatterType.valueOf(name.toUpperCase(Locale.getDefault()));
        return switch (type) {
            case JSON -> new DefaultJsonFormatter();
            case CODE -> new DefaultCodeFormatter();
            default -> throw new IllegalArgumentException("Unsupported formatter type: " + name);
        };
    }
}
