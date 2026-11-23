package ai.core.context;

import ai.core.agent.internal.ToolCallFingerprint;
import ai.core.llm.domain.FunctionCall;

import java.util.HashSet;
import java.util.Set;

/**
 * Detects the loop a small context window creates: the agent reads a large input, compression summarizes the
 * reads away, and the agent reads the exact same input again. Repeated identical calls before any compression is
 * a plain doom loop; repeated identical calls after compression means the summary cannot replace the data, so the
 * turn can never converge and burns tokens until someone cancels it.
 * <p>
 * State lives for one agent run, outside the message list, so compression cannot reset the evidence.
 *
 * @author stephen
 */
class CompressionThrashGuard {
    static final int DEFAULT_REDUNDANT_CALL_THRESHOLD = 12;

    private final int threshold;
    private final Set<ToolCallFingerprint> seen = new HashSet<>();
    private int compressions;
    private int redundantCalls;
    private FunctionCall lastRedundantCall;

    CompressionThrashGuard() {
        this(DEFAULT_REDUNDANT_CALL_THRESHOLD);
    }

    CompressionThrashGuard(int threshold) {
        this.threshold = threshold;
    }

    void beginRun() {
        seen.clear();
        compressions = 0;
        redundantCalls = 0;
        lastRedundantCall = null;
    }

    void onCompression() {
        compressions++;
    }

    /** Records every executed call; the first repeat after a compression is the signal that the context was wiped. */
    void onToolCall(FunctionCall functionCall) {
        if (functionCall == null || functionCall.function == null) {
            return;
        }
        if (seen.add(ToolCallFingerprint.of(functionCall)) || compressions == 0) {
            return;
        }
        redundantCalls++;
        lastRedundantCall = functionCall;
    }

    /** Called before the next model call so a tripped guard aborts the run instead of being swallowed as a tool error. */
    void check(int maxContextTokens) {
        if (redundantCalls < threshold || lastRedundantCall == null) {
            return;
        }
        var function = lastRedundantCall.function;
        var arguments = function.arguments == null ? "" : function.arguments.strip();
        if (arguments.length() > 200) {
            arguments = arguments.substring(0, 200) + "...";
        }
        throw new CompressionThrashException("Compression thrash detected: " + redundantCalls
                + " tool calls in this run repeated arguments already executed before " + compressions
                + " context compression(s), e.g. '" + function.name + "' with " + arguments
                + ". The model context window (" + maxContextTokens + " tokens) is too small to hold this task, so"
                + " compression keeps discarding what the agent just read. Split the task into smaller parts, or use"
                + " a model with a larger context window.");
    }

    int compressions() {
        return compressions;
    }

    int redundantCalls() {
        return redundantCalls;
    }
}
