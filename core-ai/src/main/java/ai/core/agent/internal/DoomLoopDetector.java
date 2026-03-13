package ai.core.agent.internal;

import ai.core.llm.domain.FunctionCall;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Optional;

public class DoomLoopDetector {
    private final int windowSize;
    private final int threshold;
    private final Deque<ToolCallFingerprint> recentCalls;

    public DoomLoopDetector(int windowSize, int threshold) {
        this.windowSize = windowSize;
        this.threshold = threshold;
        this.recentCalls = new ArrayDeque<>(windowSize);
    }

    public void record(FunctionCall call) {
        if (recentCalls.size() >= windowSize) {
            recentCalls.pollFirst();
        }
        recentCalls.addLast(ToolCallFingerprint.of(call));
    }

    public Optional<String> detect() {
        if (recentCalls.size() < threshold) return Optional.empty();

        var consecutive = detectConsecutiveRepeat();
        if (consecutive.isPresent()) return consecutive;

        return detectCyclicPattern();
    }

    Optional<String> detectConsecutiveRepeat() {
        var list = new ArrayList<>(recentCalls);
        var last = list.getLast();
        int count = 0;
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).equals(last)) count++;
            else break;
        }
        if (count >= threshold) {
            return Optional.of(String.format(
                    "Detected doom loop: tool '%s' called %d times consecutively with same arguments",
                    last.getToolName(), count));
        }
        return Optional.empty();
    }

    Optional<String> detectCyclicPattern() {
        var list = new ArrayList<>(recentCalls);
        for (int patternLen = 2; patternLen <= list.size() / 2; patternLen++) {
            int repeats = 1;
            boolean match = true;
            for (int i = list.size() - 2 * patternLen; i >= 0 && match; i -= patternLen) {
                for (int j = 0; j < patternLen; j++) {
                    if (!list.get(i + j).equals(list.get(list.size() - patternLen + j))) {
                        match = false;
                        break;
                    }
                }
                if (match) repeats++;
            }
            if (repeats >= 2) {
                return Optional.of(String.format(
                        "Detected cyclic doom loop: pattern of length %d repeated %d times",
                        patternLen, repeats));
            }
        }
        return Optional.empty();
    }

    public void reset() {
        recentCalls.clear();
    }

    int size() {
        return recentCalls.size();
    }
}
