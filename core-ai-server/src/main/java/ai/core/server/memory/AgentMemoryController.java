package ai.core.server.memory;

import core.framework.inject.Inject;
import core.framework.web.Request;
import core.framework.web.Response;

/**
 * @author stephen
 */
public class AgentMemoryController {
    @Inject
    AgentMemoryService agentMemoryService;

    public Response list(Request request) {
        var agentId = request.pathParam("id");
        var memories = agentMemoryService.findByAgentId(agentId);
        var views = memories.stream().map(m -> {
            var v = new AgentMemoryView();
            v.id = m.id;
            v.agentId = m.agentId;
            v.type = m.type;
            v.content = m.content;
            v.sourceTraceIds = m.sourceTraceIds;
            v.createdAt = m.createdAt;
            v.updatedAt = m.updatedAt;
            return v;
        }).toList();
        var resp = new ListAgentMemoriesResponse();
        resp.memories = views;
        return Response.bean(resp);
    }
}
