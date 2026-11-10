package ai.core.server.tool;

import ai.core.server.gateway.GatewayRoutingEngine;
import ai.core.tool.ToolCall;
import ai.core.tool.tools.DecideTool;

import java.util.List;

/**
 * Replaces the decide tool instance with one whose description lists the decision models the gateway
 * currently enables. Runs per resolution so model changes take effect without a restart.
 *
 * @author stephen
 */
final class GatewayDecisionToolEnhancer {
    static List<ToolCall> enhance(List<ToolCall> tools, GatewayRoutingEngine routingEngine) {
        if (tools.stream().noneMatch(DecideTool.class::isInstance)) return tools;
        var description = DecideTool.buildDescription(routingEngine.decisionModelHints());
        return tools.stream()
                .map(tool -> tool instanceof DecideTool ? DecideTool.builder().description(description).build() : tool)
                .toList();
    }

    private GatewayDecisionToolEnhancer() {
    }
}
