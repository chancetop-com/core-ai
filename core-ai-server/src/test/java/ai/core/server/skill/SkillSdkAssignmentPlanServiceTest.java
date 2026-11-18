package ai.core.server.skill;

import ai.core.server.domain.SkillDefinition;
import ai.core.skill.SkillSdkCapabilityManifest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SkillSdkAssignmentPlanServiceTest {
    private static SkillDefinition skill(String id, String digest, String... allowedTools) {
        var definition = new SkillDefinition();
        definition.id = id;
        definition.qualifiedName = "Admin/" + id;
        definition.digest = digest;
        definition.allowedTools = List.of(allowedTools);
        definition.metadata = Map.of(SkillSdkCapabilityManifest.CONTRACT_VERSION_KEY, "1");
        return definition;
    }

    private static SkillSdkAssignmentPlanService service(SkillDefinition... definitions) {
        var skillService = mock(SkillService.class);
        for (var definition : definitions) {
            when(skillService.findOrNull(definition.id)).thenReturn(definition);
        }
        var service = new SkillSdkAssignmentPlanService();
        service.skillService = skillService;
        return service;
    }

    @Test
    void planDigestIsStableAcrossOrderAndCapturesDeclarations() {
        var first = skill("sales", "digest-1", "api-tool:crm/leads/list", "tool:read-file");
        var second = skill("analytics", "digest-2", "mcp:analytics/run_query");
        var service = service(first, second);

        var planA = service.plan(List.of("sales", "analytics"));
        var planB = service.plan(List.of("analytics", "sales"));

        assertEquals(planA.planDigest, planB.planDigest);
        assertEquals(2, planA.skills.size());
        assertEquals(2, planA.skills.getFirst().capabilities.size());
        assertTrue(planA.skills.getFirst().capabilities.get(0).supported);
        assertTrue(planA.skills.getFirst().capabilities.get(1).supported);
    }

    @Test
    void changedSkillDigestChangesThePlan() {
        var original = service(skill("sales", "digest-1", "api-tool:crm/leads/list"))
                .plan(List.of("sales")).planDigest;
        var changed = service(skill("sales", "digest-2", "api-tool:crm/leads/list"))
                .plan(List.of("sales")).planDigest;

        assertNotEquals(original, changed);
    }
}
