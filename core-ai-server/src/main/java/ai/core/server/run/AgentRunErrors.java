package ai.core.server.run;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Failure text stored on a run record. Extracted from {@link AgentRunner} (file-size budget); the
 * truncation is the point — a stack trace from a deep native stack can be megabytes of text.
 *
 * @author stephen
 */
public final class AgentRunErrors {
    private static final int MAX_STACK_TRACE_LENGTH = 65536;

    public static String message(Throwable error) {
        return error.getMessage() != null ? error.getMessage() : error.toString();
    }

    public static String stackTrace(Throwable error) {
        var writer = new StringWriter();
        error.printStackTrace(new PrintWriter(writer));
        String trace = writer.toString();
        return trace.length() <= MAX_STACK_TRACE_LENGTH ? trace
                : trace.substring(0, MAX_STACK_TRACE_LENGTH) + "\n... stack trace truncated";
    }

    private AgentRunErrors() {
    }
}
