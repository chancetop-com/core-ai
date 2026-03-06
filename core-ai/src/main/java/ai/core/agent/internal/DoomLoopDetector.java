package ai.core.agent.internal;

import ai.core.llm.domain.FunctionCall;

import java.util.LinkedList;

public class DoomLoopDetector {
    private final int windowSize;
    private final LinkedList<String> recentCalls = new LinkedList<>();

    public DoomLoopDetector(int windowSize) {
        this.windowSize = windowSize;
    }

    public boolean record(FunctionCall call) {
        String fingerprint = call.function.name + ":" + normalizeArgs(call.function.arguments);
        recentCalls.addLast(fingerprint);
        if (recentCalls.size() > windowSize) {
            recentCalls.removeFirst();
        }
        if (recentCalls.size() < windowSize) return false;
        return recentCalls.stream().distinct().count() == 1;
    }

    public void reset() {
        recentCalls.clear();
    }

    public int getWindowSize() {
        return windowSize;
    }

    private String normalizeArgs(String args) {
        if (args == null) return "";
        return args.replaceAll("\\s+", " ").trim();
    }
}
