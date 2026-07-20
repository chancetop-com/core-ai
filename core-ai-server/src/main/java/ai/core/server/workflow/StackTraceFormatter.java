package ai.core.server.workflow;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * @author stephen
 */
public final class StackTraceFormatter {
    private static final int MAXIMUM_LENGTH = 65536;

    public static String format(Throwable error) {
        var writer = new StringWriter();
        error.printStackTrace(new PrintWriter(writer));
        String trace = writer.toString();
        return trace.length() <= MAXIMUM_LENGTH ? trace : trace.substring(0, MAXIMUM_LENGTH) + "\n... stack trace truncated";
    }

    private StackTraceFormatter() {
    }
}
