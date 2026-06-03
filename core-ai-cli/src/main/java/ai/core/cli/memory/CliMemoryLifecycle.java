package ai.core.cli.memory;

import ai.core.agent.Agent;
import ai.core.agent.ExecutionContext;
import ai.core.agent.lifecycle.AbstractLifecycle;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thin {@link AbstractLifecycle} adapter that delegates agent lifecycle hooks
 * to {@link MemoryTriggerService} for memory extraction triggering.
 *
 * <p>Registers memory tools (KnowledgeLogTool, ExtractionCursorTool) on the main
 * agent during {@code afterAgentBuild}, so forked extraction agents inherit them.
 *
 * <p>Intended usage:
 * <pre>{@code
 *   var service = new MemoryTriggerService(llmProvider, model, workspace);
 *   builder.addAgentLifecycle(new CliMemoryLifecycle(service));
 *   // afterAgentBuild calls service.init(agent) and registers memory tools
 * }</pre>
 */
public class CliMemoryLifecycle extends AbstractLifecycle {

    private final MemoryTriggerService service;
    private final boolean dailyLogsEnabled;

    public CliMemoryLifecycle(MemoryTriggerService service, boolean dailyLogsEnabled) {
        this.service = service;
        this.dailyLogsEnabled = dailyLogsEnabled;
    }

    @Override
    public void afterAgentBuild(Agent agent) {
        service.setDailyLogsEnabled(dailyLogsEnabled);
        service.init(agent);
        var tools = new ArrayList<>(service.buildMemoryTools(agent));
        agent.addTools(tools);
    }

    @Override
    public void beforeAgentRun(AtomicReference<String> query, ExecutionContext executionContext) {
        service.onAgentStart();
        service.onUserActivity();
    }

    @Override
    public void afterAgentRun(String query, AtomicReference<String> result, ExecutionContext executionContext) {
        service.onAgentEnd();
        service.onTurnComplete();
    }
}
