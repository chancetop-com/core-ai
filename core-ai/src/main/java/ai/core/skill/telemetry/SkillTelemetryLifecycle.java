package ai.core.skill.telemetry;

import ai.core.agent.ExecutionContext;
import ai.core.agent.lifecycle.AbstractLifecycle;
import ai.core.llm.domain.FunctionCall;
import ai.core.skill.SkillMetadata;
import ai.core.skill.SkillRegistry;
import ai.core.tool.ToolCallResult;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Agent lifecycle hook that attributes SDK capability and script executions to mounted skills
 * and triggers non-blocking telemetry flush when a run completes or fails.
 *
 * @author stephen
 */
public class SkillTelemetryLifecycle extends AbstractLifecycle {
    private final Supplier<List<SkillMetadata>> attachedSkillsSupplier;
    private final Function<SkillMetadata, SkillTelemetryIdentity> identityResolver;
    private final SkillTelemetrySink sink;
    private final Runnable onRunEnd;

    public SkillTelemetryLifecycle(SkillRegistry registry,
                                   Function<SkillMetadata, SkillTelemetryIdentity> identityResolver,
                                   SkillTelemetrySink sink,
                                   Runnable onRunEnd) {
        this(registry == null ? List::of : registry::listAll, identityResolver, sink, onRunEnd);
    }

    public SkillTelemetryLifecycle(Supplier<List<SkillMetadata>> attachedSkillsSupplier,
                                   Function<SkillMetadata, SkillTelemetryIdentity> identityResolver,
                                   SkillTelemetrySink sink,
                                   Runnable onRunEnd) {
        this.attachedSkillsSupplier = attachedSkillsSupplier != null ? attachedSkillsSupplier : List::of;
        this.identityResolver = identityResolver;
        this.sink = sink != null ? sink : SkillTelemetrySink.NOOP;
        this.onRunEnd = onRunEnd;
    }

    @Override
    public void afterTool(FunctionCall functionCall, ExecutionContext executionContext, ToolCallResult toolResult) {
        SkillScriptAttributor.attributeToolCall(
                functionCall,
                executionContext,
                toolResult,
                attachedSkillsSupplier.get(),
                identityResolver,
                sink);
    }

    @Override
    public void afterAgentRun(String query, AtomicReference<String> result, ExecutionContext executionContext) {
        triggerFlush();
    }

    @Override
    public void afterAgentFailed(String query, ExecutionContext executionContext, Exception exception) {
        triggerFlush();
    }

    private void triggerFlush() {
        if (onRunEnd != null) {
            onRunEnd.run();
        }
    }
}
