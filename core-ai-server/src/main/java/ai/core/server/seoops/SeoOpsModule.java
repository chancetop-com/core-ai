package ai.core.server.seoops;

import ai.core.api.server.seoops.SeoOpsWebService;
import core.framework.module.Module;

/**
 * Feature-gated SEO operations control-plane module.
 *
 * @author xander
 */
public class SeoOpsModule extends Module {
    @Override
    protected void initialize() {
        var enabled = "true".equalsIgnoreCase(property("sys.seoops.enabled").orElse("false"));
        if (!enabled) return;
        var rawAgentId = property("sys.seoops.copilot.agent-id").orElse(null);
        var agentId = rawAgentId == null || rawAgentId.isBlank() ? null : rawAgentId.trim();
        bind(new SeoOpsRuntimeConfig(true, agentId));
        bind(SeoExecutionSpecHasher.class);
        bind(SeoTaskPolicy.class);
        bind(SeoConversationPolicy.class);
        bind(SeoCopilotPolicy.class);
        bind(SeoMerchantService.class);
        bind(SeoTaskCommandService.class);
        bind(SeoOpsViewMapper.class);
        bind(SeoOpsQueryService.class);
        api().service(SeoOpsWebService.class, bind(SeoOpsWebServiceImpl.class));
    }
}
