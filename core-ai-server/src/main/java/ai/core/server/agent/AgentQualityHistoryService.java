package ai.core.server.agent;

import ai.core.api.server.agent.AgentQualityRunListResponse;
import ai.core.server.quality.QualityPublicationService;
import ai.core.server.quality.QualityReportMapper;
import core.framework.inject.Inject;

/**
 * Read-only view of one agent's quality runs. The write-side gate stays in PublishQualityGateService;
 * this service only narrows the history to the requested agent.
 *
 * @author core-ai
 */
public class AgentQualityHistoryService {
    @Inject
    AgentDefinitionService agentDefinitionService;
    @Inject
    QualityPublicationService qualityPublicationService;

    public AgentQualityRunListResponse runs(String agentId, String userId) {
        agentDefinitionService.requireQualityAccess(agentId, userId);
        var response = new AgentQualityRunListResponse();
        response.runs = QualityReportMapper.runViews(qualityPublicationService.runs(agentId, 50));
        return response;
    }
}
