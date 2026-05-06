package ai.core.server.a2a;

import ai.core.api.a2a.SendMessageRequest;
import core.framework.web.Request;
import core.framework.web.exception.BadRequestException;

/**
 * Resolves server agent ids from A2A HTTP bindings.
 *
 * @author xander
 */
final class A2AAgentIdResolver {
    static String resolve(Request request) {
        var pathAgentId = request.pathParam("agentId");
        if (pathAgentId != null && !pathAgentId.isBlank()) return pathAgentId;
        throw new BadRequestException("agentId required");
    }

    static String resolve(Request request, SendMessageRequest messageRequest) {
        var pathAgentId = request.pathParam("agentId");
        if (pathAgentId != null && !pathAgentId.isBlank()) return pathAgentId;
        return resolveStream(request, messageRequest);
    }

    static String resolveStream(Request request, SendMessageRequest messageRequest) {
        var agentId = request.queryParams().get("agent_id");
        if (agentId != null && !agentId.isBlank()) return agentId;
        agentId = request.queryParams().get("agentId");
        if (agentId != null && !agentId.isBlank()) return agentId;
        if (messageRequest != null && messageRequest.tenant != null && !messageRequest.tenant.isBlank()) {
            return messageRequest.tenant;
        }
        if (messageRequest != null && messageRequest.metadata != null) {
            var value = messageRequest.metadata.get("agentId");
            if (value != null) return String.valueOf(value);
            value = messageRequest.metadata.get("agent_id");
            if (value != null) return String.valueOf(value);
        }
        throw new BadRequestException("agentId required");
    }

    private A2AAgentIdResolver() {
    }
}
