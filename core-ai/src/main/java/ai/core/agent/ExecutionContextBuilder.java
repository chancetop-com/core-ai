package ai.core.agent;

import ai.core.agent.budget.RunBudget;
import ai.core.persistence.PersistenceProvider;
import ai.core.prompt.PromptInject;
import ai.core.sandbox.Sandbox;
import ai.core.session.BackgroundTaskManager;
import ai.core.tool.OutboundCallerContext.Caller;
import ai.core.tool.ToolCallAsyncTaskManager;
import ai.core.tool.subagent.SubagentOutputSinkFactory;
import ai.core.tool.tools.TodoStoreFactory;
import core.framework.util.Maps;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builder for {@link ExecutionContext}. Kept separate from the context so the
 * runtime class stays within the repository file-length limit.
 *
 * @author stephen
 */
public final class ExecutionContextBuilder {
    String sessionId;
    String userId;
    Caller caller;
    String taskId;
    String taskName;
    ToolCallAsyncTaskManager asyncTaskManager;
    AttachedContent attachedContent;
    PersistenceProvider persistenceProvider;
    SubagentOutputSinkFactory subagentOutputSinkFactory;
    TodoStoreFactory todoStoreFactory;
    final List<PromptInject> promptSections = new ArrayList<>();
    Sandbox sandbox;
    BackgroundTaskManager backgroundTaskManager;
    CancellationToken cancellationToken;
    RunBudget runBudget;
    final Map<String, Object> customVariables = Maps.newHashMap();

    public ExecutionContextBuilder sessionId(String sessionId) {
        this.sessionId = sessionId;
        return this;
    }

    public ExecutionContextBuilder userId(String userId) {
        this.userId = userId;
        return this;
    }

    public ExecutionContextBuilder caller(Caller caller) {
        this.caller = caller;
        return this;
    }

    public ExecutionContextBuilder taskId(String taskId) {
        this.taskId = taskId;
        return this;
    }

    public ExecutionContextBuilder taskName(String taskName) {
        this.taskName = taskName;
        return this;
    }

    public ExecutionContextBuilder customVariables(Map<String, Object> customVariables) {
        if (customVariables != null) {
            this.customVariables.putAll(customVariables);
        }
        return this;
    }

    public ExecutionContextBuilder customVariable(String key, Object value) {
        this.customVariables.put(key, value);
        return this;
    }

    public ExecutionContextBuilder asyncTaskManager(ToolCallAsyncTaskManager asyncTaskManager) {
        this.asyncTaskManager = asyncTaskManager;
        return this;
    }

    public ExecutionContextBuilder attachedContent(AttachedContent attachedContent) {
        this.attachedContent = attachedContent;
        return this;
    }

    public ExecutionContextBuilder persistenceProvider(PersistenceProvider persistenceProvider) {
        this.persistenceProvider = persistenceProvider;
        return this;
    }

    public ExecutionContextBuilder subagentOutputSinkFactory(SubagentOutputSinkFactory subagentOutputSinkFactory) {
        this.subagentOutputSinkFactory = subagentOutputSinkFactory;
        return this;
    }

    public ExecutionContextBuilder todoStoreFactory(TodoStoreFactory todoStoreFactory) {
        this.todoStoreFactory = todoStoreFactory;
        return this;
    }

    public ExecutionContextBuilder promptSections(List<PromptInject> sections) {
        this.promptSections.clear();
        this.promptSections.addAll(sections);
        return this;
    }

    public ExecutionContextBuilder sandbox(Sandbox sandbox) {
        this.sandbox = sandbox;
        return this;
    }

    public ExecutionContextBuilder taskManager(BackgroundTaskManager backgroundTaskManager) {
        this.backgroundTaskManager = backgroundTaskManager;
        return this;
    }

    public ExecutionContextBuilder cancellationToken(CancellationToken cancellationToken) {
        this.cancellationToken = cancellationToken;
        return this;
    }

    public ExecutionContextBuilder runBudget(RunBudget runBudget) {
        this.runBudget = runBudget;
        return this;
    }

    public ExecutionContext build() {
        return new ExecutionContext(this);
    }
}
