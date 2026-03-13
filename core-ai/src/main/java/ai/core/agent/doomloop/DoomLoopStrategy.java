package ai.core.agent.doomloop;

import ai.core.agent.ExecutionContext;
import ai.core.llm.domain.CompletionRequest;

public interface DoomLoopStrategy {
    boolean detect(CompletionRequest request, ExecutionContext context);

    String warningMessage();
}
