package ai.core.server.comparison;

import ai.core.api.server.comparison.AgentComparisonWebService;
import core.framework.module.Module;

/**
 * Agent version comparison test (online A/B). Loads after SessionModule (AgentSessionManager) and
 * AgentDefinitionModule (AgentDefinitionService), which its services inject.
 *
 * @author stephen
 */
public class ComparisonModule extends Module {
    @Override
    protected void initialize() {
        bind(ComparisonViewAssembler.class);
        bind(AgentComparisonService.class);
        bind(ComparisonSessionService.class);
        api().service(AgentComparisonWebService.class, bind(AgentComparisonWebServiceImpl.class));
    }
}
