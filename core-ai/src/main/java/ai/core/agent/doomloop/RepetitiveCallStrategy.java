package ai.core.agent.doomloop;

import ai.core.agent.ExecutionContext;
import ai.core.agent.internal.ToolCallFingerprint;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.RoleType;

import java.util.ArrayList;
import java.util.List;

public class RepetitiveCallStrategy implements DoomLoopStrategy {
    static final int DEFAULT_WINDOW_SIZE = 4;
    static final int DEFAULT_THRESHOLD = 3;

    private final int windowSize;
    private final int threshold;
    private String lastDetectedMessage;

    public RepetitiveCallStrategy() {
        this(DEFAULT_WINDOW_SIZE, DEFAULT_THRESHOLD);
    }

    public RepetitiveCallStrategy(int windowSize, int threshold) {
        this.windowSize = windowSize;
        this.threshold = threshold;
    }

    @Override
    public boolean detect(CompletionRequest request, ExecutionContext context) {
        lastDetectedMessage = null;
        var fingerprints = extractRecentFingerprints(request);
        if (fingerprints.size() < threshold) return false;

        var consecutive = detectConsecutiveRepeat(fingerprints);
        if (consecutive != null) {
            lastDetectedMessage = consecutive;
            return true;
        }
        var cyclic = detectCyclicPattern(fingerprints);
        if (cyclic != null) {
            lastDetectedMessage = cyclic;
            return true;
        }
        return false;
    }

    @Override
    public String warningMessage() {
        return lastDetectedMessage != null ? lastDetectedMessage : "";
    }

    List<ToolCallFingerprint> extractRecentFingerprints(CompletionRequest request) {
        var fingerprints = new ArrayList<ToolCallFingerprint>();
        for (int i = request.messages.size() - 1; i >= 0 && fingerprints.size() < windowSize; i--) {
            var msg = request.messages.get(i);
            if (RoleType.ASSISTANT.equals(msg.role) && msg.toolCalls != null) {
                for (int j = msg.toolCalls.size() - 1; j >= 0 && fingerprints.size() < windowSize; j--) {
                    fingerprints.addFirst(ToolCallFingerprint.of(msg.toolCalls.get(j)));
                }
            }
        }
        return fingerprints;
    }

    String detectConsecutiveRepeat(List<ToolCallFingerprint> list) {
        if (list.isEmpty()) return null;
        var last = list.getLast();
        int count = 0;
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).equals(last)) count++;
            else break;
        }
        if (count >= threshold) {
            return String.format(
                    "[SYSTEM WARNING] Detected doom loop: tool '%s' called %d times consecutively with same arguments. "
                            + "You are repeating the same action without making progress. "
                            + "Please try a different approach or ask the user for help.",
                    last.getToolName(), count);
        }
        return null;
    }

    String detectCyclicPattern(List<ToolCallFingerprint> list) {
        for (int patternLen = 2; patternLen <= list.size() / 2; patternLen++) {
            int repeats = countPatternRepeats(list, patternLen);
            if (repeats >= 2) {
                return String.format(
                        "[SYSTEM WARNING] Detected cyclic doom loop: pattern of length %d repeated %d times. "
                                + "You are repeating the same sequence without making progress. "
                                + "Please try a different approach or ask the user for help.",
                        patternLen, repeats);
            }
        }
        return null;
    }

    private int countPatternRepeats(List<ToolCallFingerprint> list, int patternLen) {
        int repeats = 1;
        for (int i = list.size() - 2 * patternLen; i >= 0; i -= patternLen) {
            if (!matchesPattern(list, i, patternLen)) break;
            repeats++;
        }
        return repeats;
    }

    private boolean matchesPattern(List<ToolCallFingerprint> list, int offset, int patternLen) {
        for (int j = 0; j < patternLen; j++) {
            if (!list.get(offset + j).equals(list.get(list.size() - patternLen + j))) {
                return false;
            }
        }
        return true;
    }
}
