package ai.core.server;

import ai.core.server.quality.AgentQualityAdminService;
import ai.core.server.quality.BuiltinCapabilityRule;
import ai.core.server.quality.DecisionQualityRule;
import ai.core.server.quality.EffectiveCapabilityResolver;
import ai.core.server.quality.FunctionSummaryRule;
import ai.core.server.quality.McpServerAvailableRule;
import ai.core.server.quality.McpToolAvailableRule;
import ai.core.server.quality.PublishCandidateBuilder;
import ai.core.server.quality.PublishQualityGate;
import ai.core.server.quality.PublishQualityGateService;
import ai.core.server.quality.QualityDecisionGateway;
import ai.core.server.quality.QualityPolicyService;
import ai.core.server.quality.QualityPublicationService;
import ai.core.server.quality.QualityReferenceResolver;
import ai.core.server.quality.QualityRuleRegistry;
import ai.core.server.quality.QualitySnapshotService;
import ai.core.server.quality.ReferenceAvailableRule;
import ai.core.server.quality.ResourceBindingRule;
import ai.core.server.quality.ResourceCountLimitRule;
import ai.core.server.quality.TimeContextRule;
import ai.core.server.web.AgentQualityAdminWebServiceImpl;
import core.framework.module.Module;

/**
 * Binds the publish gate before AgentDefinitionModule, which resolves it while constructing the agent
 * service. Evaluators are registered explicitly: the registry is a bounded extension point, not a classpath
 * scan that could execute arbitrary uploads.
 *
 * @author core-ai
 */
public class QualityGateModule extends Module {
    @Override
    protected void initialize() {
        var registry = bind(QualityRuleRegistry.class);
        registry.register(bind(ReferenceAvailableRule.class));
        registry.register(bind(McpServerAvailableRule.class));
        registry.register(bind(McpToolAvailableRule.class));
        registry.register(bind(ResourceCountLimitRule.class));

        bind(QualityReferenceResolver.class);
        bind(EffectiveCapabilityResolver.class);

        registry.register(bind(TimeContextRule.class));
        registry.register(bind(BuiltinCapabilityRule.class));
        registry.register(bind(ResourceBindingRule.class));
        registry.register(bind(FunctionSummaryRule.class));
        registry.register(bind(DecisionQualityRule.class));

        var decisions = bind(QualityDecisionGateway.class);
        decisions.setRoutingEngine(bean(ai.core.server.gateway.GatewayRoutingEngine.class));

        var policyService = bind(QualityPolicyService.class);
        bind(PublishCandidateBuilder.class);
        bind(QualitySnapshotService.class);
        bind(QualityPublicationService.class);

        var gate = bind(PublishQualityGateService.class);
        bind(PublishQualityGate.class, gate);
        bind(AgentQualityAdminService.class);
        api().service(ai.core.api.server.AgentQualityAdminWebService.class,
            bind(AgentQualityAdminWebServiceImpl.class));

        onStartup(policyService::seed);
    }
}
