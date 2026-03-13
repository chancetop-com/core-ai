package ai.core.agent.doomloop;

import ai.core.agent.ExecutionContext;
import ai.core.agent.lifecycle.AbstractLifecycle;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.RoleType;

import java.util.ArrayList;
import java.util.List;

public class DoomLoopLifecycle extends AbstractLifecycle {
    private final List<DoomLoopStrategy> strategies;

    public DoomLoopLifecycle(List<DoomLoopStrategy> strategies) {
        this.strategies = new ArrayList<>(strategies);
    }

    @Override
    public void beforeModel(CompletionRequest request, ExecutionContext context) {
        if (request == null || request.messages == null || request.messages.isEmpty()) return;

        for (var strategy : strategies) {
            if (strategy.detect(request, context)) {
                appendWarning(request, strategy.warningMessage());
            }
        }
    }

    void appendWarning(CompletionRequest request, String warning) {
        for (int i = request.messages.size() - 1; i >= 0; i--) {
            var msg = request.messages.get(i);
            if (RoleType.TOOL.equals(msg.role)) {
                var currentText = msg.getTextContent();
                if (currentText != null && currentText.contains(warning)) return;
                msg.content.getFirst().text = String.join("\n", currentText == null ? "" : currentText, warning);
                return;
            }
        }
    }
}
