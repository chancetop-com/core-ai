package ai.core.server.quality;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class AgentQualityAdminServiceTest {
    private AgentQualityAdminService service() {
        var registry = new QualityRuleRegistry();
        registry.register(new ReferenceAvailableRule());
        registry.register(new BuiltinCapabilityRule());
        registry.register(new FunctionSummaryRule());
        registry.register(new ResourceBindingRule());
        registry.register(new TimeContextRule());

        var service = new AgentQualityAdminService();
        service.policyService = mock(QualityPolicyService.class);
        service.registry = registry;
        return service;
    }

    @Test
    void templatesCarryTheQuestionsBuiltInRulesAsk() {
        var templates = service().templates("admin").templates;

        var capability = template(templates, BuiltinCapabilityRule.KEY);
        assertEquals(EffectiveCapabilityResolver.CAPABILITIES.size(), capability.questions.size());
        assertEquals("intent_WEB_FETCH", capability.questions.get(3).name);
        assertEquals("noul", capability.questions.get(3).type);
        assertTrue(capability.questions.get(3).instruction.contains("抓取或打开某个 URL"));

        assertEquals(1, template(templates, FunctionSummaryRule.KEY).questions.size());
        assertEquals("function_summary_present", template(templates, FunctionSummaryRule.KEY).questions.get(0).name);
        assertEquals(1, template(templates, ResourceBindingRule.KEY).questions.size());
        assertEquals(1, template(templates, TimeContextRule.KEY).questions.size());
    }

    @Test
    void codeTemplatesDoNotPretendToAskQuestions() {
        var templates = service().templates("admin").templates;

        assertTrue(template(templates, ReferenceAvailableRule.KEY).questions.isEmpty());
    }

    private ai.core.api.server.agent.AgentQualityRuleTemplateView template(
        List<ai.core.api.server.agent.AgentQualityRuleTemplateView> templates, String evaluatorKey) {
        return templates.stream().filter(template -> evaluatorKey.equals(template.evaluatorKey)).findFirst()
            .orElseThrow(() -> new AssertionError("missing template: " + evaluatorKey));
    }
}
