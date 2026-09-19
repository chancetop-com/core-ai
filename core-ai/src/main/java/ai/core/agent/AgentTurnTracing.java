package ai.core.agent;

import ai.core.telemetry.context.AgentTraceContext;

import java.util.Locale;

/**
 * Turn-scoped trace context assembly for {@link Agent}: a user turn and a runtime-injected continuation
 * turn must produce the same span shape, because that span carries the session identity the trace store
 * resolves names, session attribution and per-session cost rollups from.
 *
 * @author stephen
 */
final class AgentTurnTracing {
    static AgentTraceContext start(Agent agent, String input) {
        var execContext = agent.getExecutionContext();
        return AgentTraceContext.builder()
                .name(agent.getName())
                .id(agent.getId())
                .input(input)
                .withTools(agent.toolRegistry != null && !agent.toolRegistry.getToolCalls().isEmpty())
                .withRag(agent.ragConfig != null && agent.ragConfig.useRag())
                .sessionId(execContext.getSessionId())
                .userId(execContext.getUserId())
                .build();
    }

    static void complete(Agent agent, AgentTraceContext context) {
        context.setOutput(agent.getOutput());
        context.setStatus(agent.getNodeStatus().name());
        context.setMessageCount(agent.getMessages().size());
        var token = agent.getExecutionContext().getCancellationToken();
        context.setCancelReason(token != null && token.getReason() != null
                ? token.getReason().name().toLowerCase(Locale.ENGLISH) : null);
    }

    private AgentTurnTracing() {
    }
}
