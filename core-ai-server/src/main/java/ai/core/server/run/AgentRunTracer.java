package ai.core.server.run;

import ai.core.agent.Agent;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentRun;
import ai.core.server.domain.TriggerType;
import ai.core.telemetry.TelemetryConfig;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;

/**
 * Tracing infrastructure for agent runs — span creation, workflow trace attribute propagation,
 * and run-to-trace-id linkage.
 *
 * @author stephen
 */
public class AgentRunTracer {
    static final AttributeKey<String> LANGFUSE_OBSERVATION_TYPE = AttributeKey.stringKey("langfuse.observation.type");
    static final AttributeKey<String> GEN_AI_OPERATION_NAME = AttributeKey.stringKey("gen_ai.operation.name");
    static final AttributeKey<String> GEN_AI_AGENT_NAME = AttributeKey.stringKey("gen_ai.agent.name");
    static final AttributeKey<String> GEN_AI_AGENT_ID = AttributeKey.stringKey("gen_ai.agent.id");
    static final AttributeKey<String> INPUT_VALUE = AttributeKey.stringKey("gen_ai.prompt");
    static final AttributeKey<String> OUTPUT_VALUE = AttributeKey.stringKey("gen_ai.completion");
    static final AttributeKey<String> AGENT_STATUS = AttributeKey.stringKey("agent.status");
    static final AttributeKey<String> SESSION_ID = AttributeKey.stringKey("session.id");
    static final AttributeKey<String> USER_ID = AttributeKey.stringKey("user.id");
    static final AttributeKey<String> CLIENT_TYPE = AttributeKey.stringKey("client.type");
    static final AttributeKey<String> CORE_AI_RUN_ID = AttributeKey.stringKey("core_ai.run_id");
    static final AttributeKey<String> CORE_AI_SCHEDULE_ID = AttributeKey.stringKey("core_ai.schedule_id");
    static final AttributeKey<String> CORE_AI_WORKFLOW_ID = AttributeKey.stringKey("core_ai.workflow_id");
    static final AttributeKey<String> CORE_AI_WORKFLOW_RUN_ID = AttributeKey.stringKey("core_ai.workflow_run_id");
    static final AttributeKey<String> CORE_AI_WORKFLOW_NODE_ID = AttributeKey.stringKey("core_ai.workflow_node_id");
    static final AttributeKey<String> CORE_AI_WORKFLOW_NODE_TYPE = AttributeKey.stringKey("core_ai.workflow_node_type");

    static TriggerType triggerFor(AgentRun runEntity) {
        return runEntity.triggeredBy != null ? runEntity.triggeredBy : TriggerType.MANUAL;
    }

    static String traceSource(TriggerType trigger) {
        return switch (trigger) {
            case SCHEDULE -> "scheduled";
            case WEBHOOK, API, MANUAL -> "api";
            case WORKFLOW -> "workflow";
        };
    }

    @Inject
    TelemetryConfig telemetryConfig;
    @Inject
    MongoCollection<AgentRun> agentRunCollection;

    String runAgentWithTrace(AgentRun runEntity, AgentDefinition definition, Agent agent,
                            WorkflowTraceContext traceContext) {
        return runWithTrace(runEntity, definition, traceContext, "agent.run", span -> {
            var output = agent.run(runEntity.input);
            if (output != null) span.setAttribute(OUTPUT_VALUE, output);
            if (agent.getNodeStatus() != null) span.setAttribute(AGENT_STATUS, agent.getNodeStatus().name());
            return output;
        });
    }

    LLMCallExecutor.Result runLLMCallWithTrace(AgentRun runEntity, AgentDefinition definition,
                              WorkflowTraceContext traceContext, LLMCallExecutor llmCallExecutor) {
        return runWithTrace(runEntity, definition, traceContext, "llm_call.run", span -> {
            var result = llmCallExecutor.execute(definition, runEntity.input);
            if (result.output() != null) span.setAttribute(OUTPUT_VALUE, result.output());
            return result;
        });
    }

    @SuppressWarnings({"try", "PMD.UnusedLocalVariable"})
    <T> T runWithTrace(AgentRun runEntity, AgentDefinition definition, WorkflowTraceContext traceContext,
                       String spanName, TraceCallable<T> callable) {
        var spanBuilder = telemetryConfig.getOpenTelemetry().getTracer("core-ai-server", "1.0.0")
                .spanBuilder(spanName)
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute(LANGFUSE_OBSERVATION_TYPE, "agent")
                .setAttribute(GEN_AI_OPERATION_NAME, "agent")
                .setAttribute(SESSION_ID, "run:" + runEntity.id)
                .setAttribute(CLIENT_TYPE, traceSource(triggerFor(runEntity)))
                .setAttribute(CORE_AI_RUN_ID, runEntity.id);
        addWorkflowTraceAttributes(spanBuilder, traceContext);
        if (definition.id != null) spanBuilder.setAttribute(GEN_AI_AGENT_ID, definition.id);
        if (definition.name != null) spanBuilder.setAttribute(GEN_AI_AGENT_NAME, definition.name);
        if (definition.userId != null) spanBuilder.setAttribute(USER_ID, definition.userId);
        var span = spanBuilder.startSpan();
        var spanContext = span.getSpanContext();
        if (spanContext.isValid()) {
            setRunTraceId(runEntity, spanContext.getTraceId());
        }
        if (runEntity.scheduleId != null) span.setAttribute(CORE_AI_SCHEDULE_ID, runEntity.scheduleId);
        if (runEntity.input != null) span.setAttribute(INPUT_VALUE, runEntity.input);
        try (var scope = span.makeCurrent()) {
            return callable.call(span);
        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }

    void addWorkflowTraceAttributes(SpanBuilder spanBuilder, WorkflowTraceContext traceContext) {
        if (traceContext == null) return;
        setSpanAttribute(spanBuilder, CORE_AI_WORKFLOW_ID, traceContext.workflowId());
        setSpanAttribute(spanBuilder, CORE_AI_WORKFLOW_RUN_ID, traceContext.workflowRunId());
        setSpanAttribute(spanBuilder, CORE_AI_WORKFLOW_NODE_ID, traceContext.workflowNodeId());
        setSpanAttribute(spanBuilder, CORE_AI_WORKFLOW_NODE_TYPE, traceContext.workflowNodeType());
    }

    private void setSpanAttribute(SpanBuilder spanBuilder, AttributeKey<String> key, String value) {
        if (value != null && !value.isBlank()) spanBuilder.setAttribute(key, value);
    }

    private void setRunTraceId(AgentRun runEntity, String traceId) {
        if (traceId == null || traceId.isBlank()) return;
        runEntity.traceId = traceId;
        agentRunCollection.update(
                Filters.and(
                        Filters.eq("_id", runEntity.id),
                        Filters.or(Filters.exists("trace_id", false), Filters.eq("trace_id", null), Filters.eq("trace_id", ""))
                ),
                Updates.set("trace_id", traceId)
        );
    }
}
