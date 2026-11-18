package ai.core.server;

import ai.core.server.skill.quality.SkillCodeRuleEvaluator;
import ai.core.server.skill.quality.SkillDecisionQualityRule;
import ai.core.server.skill.quality.SkillQualityGate;
import ai.core.server.skill.quality.SkillQualityPolicyService;
import ai.core.server.skill.quality.SkillQualityRuleRegistry;
import core.framework.module.Module;

/**
 * Binds the skill rule catalog and policy before SkillModule resolves the publish gate. The shared
 * decision gateway is wired later, because QualityGateModule has to load after SkillModule.
 *
 * @author core-ai
 */
public class SkillQualityModule extends Module {
    @Override
    protected void initialize() {
        var registry = bind(SkillQualityRuleRegistry.class);
        for (var rule : SkillQualityGate.builtInRules()) {
            registry.register(new SkillCodeRuleEvaluator(rule));
        }
        registry.register(bind(SkillDecisionQualityRule.class));

        var policyService = bind(SkillQualityPolicyService.class);
        bind(SkillQualityGate.class);
        onStartup(policyService::seed);
    }
}
