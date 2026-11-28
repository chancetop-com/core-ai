package ai.core.skill;

import ai.core.agent.ExecutionContext;
import ai.core.session.CustomEventEmitter;
import ai.core.tool.ToolSearchScorer;
import ai.core.tool.tools.ReportSkillPapercutTool;
import ai.core.tool.tools.SearchSkillsTool;
import ai.core.tool.tools.SkillTool;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for calibrated SkillSearchScorer (CORE_TIER_BONUS 10 -> 3), SkillSlotAllocator,
 * SearchSkillsTool, and ReportSkillPapercutTool rate limiting and attachment enforcement.
 *
 * @author stephen
 */
class SkillDiscoveryAndPapercutTest {
    @Test
    void coreTierBonusThreePreservesNameHitOverWeakCoreDescriptionHitWhileBreakingTies() {
        var domainNameHit = new SkillSearchScorer.Candidate(
                "billing", "invoice-parser", "Extracts fields from PDFs", List.of(), Map.of(), false);
        var coreDescOnly = new SkillSearchScorer.Candidate(
                "builtin", "finance-helper", "Helps review invoice and ledger docs", List.of(), Map.of(), true);
        var domainTie = new SkillSearchScorer.Candidate(
                "billing", "invoice-audit", "Audit invoice records", List.of(), Map.of(), false);
        var coreTie = new SkillSearchScorer.Candidate(
                "builtin", "invoice-audit", "Audit invoice records", List.of(), Map.of(), true);

        var tokens = ToolSearchScorer.tokenize("invoice");
        int legacyDomain = SkillSearchScorer.score(domainNameHit, tokens, "invoice", SkillSearchScorer.LEGACY_CORE_TIER_BONUS);
        int legacyCore = SkillSearchScorer.score(coreDescOnly, tokens, "invoice", SkillSearchScorer.LEGACY_CORE_TIER_BONUS);
        assertEquals(10, legacyDomain);
        assertEquals(12, legacyCore);
        assertTrue(legacyCore > legacyDomain, "Legacy bonus=10 wrongly ranked description-only CORE above name-hit DOMAIN");

        int calibratedDomain = SkillSearchScorer.score(domainNameHit, tokens, "invoice", SkillSearchScorer.CORE_TIER_BONUS);
        int calibratedCore = SkillSearchScorer.score(coreDescOnly, tokens, "invoice", SkillSearchScorer.CORE_TIER_BONUS);
        assertEquals(10, calibratedDomain);
        assertEquals(5, calibratedCore);
        assertTrue(calibratedDomain > calibratedCore, "Calibrated bonus=3 keeps name-hit DOMAIN above description-only CORE");

        int tieDomain = SkillSearchScorer.score(domainTie, tokens, "invoice");
        int tieCore = SkillSearchScorer.score(coreTie, tokens, "invoice");
        assertEquals(12, tieDomain);
        assertEquals(15, tieCore);
        assertTrue(tieCore > tieDomain, "Calibrated bonus=3 still lets CORE win equal-relevance ties");
    }

    @Test
    void slotAllocatorReservesAlwaysOnSlotsAndCapsInlinePromptAtTwentyFive() {
        var skills = new ArrayList<SkillMetadata>(32);
        for (int i = 0; i < 22; i++) {
            skills.add(SkillMetadata.builder("domain-" + i, "Domain skill " + i, "/d/" + i)
                    .namespace("team").tier("DOMAIN").build());
        }
        for (int i = 0; i < 10; i++) {
            skills.add(SkillMetadata.builder("base-" + i, "Always on " + i, "/a/" + i)
                    .namespace("builtin").tier("ALWAYS_ON").build());
        }

        var allocation = SkillSlotAllocator.allocate(skills);
        assertEquals(SkillSlotAllocator.PINNED_SKILL_CAP, allocation.pinned().size());
        assertEquals(7, allocation.overflow().size());
        long pinnedAlwaysOn = allocation.pinned().stream().filter(s -> "ALWAYS_ON".equals(s.getTier())).count();
        assertEquals(10L, pinnedAlwaysOn);

        var desc = SkillTool.buildDescription(skills, null);
        assertTrue(desc.contains("builtin/base-0"));
        assertTrue(desc.contains("builtin/base-9"));
        assertTrue(desc.contains("Additional attached skills (7)"));
        assertTrue(desc.contains("search_skills(query)"));
    }

    @Test
    void reportSkillPapercutRestrictsToAttachedSkillsAndEnforcesTwoPerSessionLimit() {
        var attached = SkillMetadata.builder("deploy", "Deploy flow", "/deploy/SKILL.md")
                .skillId("skill-deploy").namespace("ops").tier("ALWAYS_ON").build();
        var registry = new SkillRegistry();
        registry.addProvider(new StaticSkillProvider(List.of(attached)));

        var submitted = new AtomicInteger();
        var tool = ReportSkillPapercutTool.builder()
                .registry(registry)
                .submitter(sub -> {
                    submitted.incrementAndGet();
                    return "ok-" + submitted.get();
                })
                .build();

        var ctx = ExecutionContext.builder().sessionId("sess-1").userId("user-1").build();

        var unattached = tool.execute(
                "{\"skill\":\"ops/unknown\",\"category\":\"SCRIPT_ERROR\",\"summary\":\"Unknown skill error test\"}", ctx);
        assertTrue(unattached.isFailed());
        assertTrue(unattached.getResult().contains(ReportSkillPapercutTool.ERROR_NOT_ATTACHED));

        var first = tool.execute(
                "{\"skill\":\"ops/deploy\",\"category\":\"SCRIPT_ERROR\",\"summary\":\"Deploy script fails on missing env\"}", ctx);
        var second = tool.execute(
                "{\"skill\":\"ops/deploy\",\"category\":\"OUTDATED_DOC\",\"summary\":\"Deploy flags changed in CLI v2\"}", ctx);
        var third = tool.execute(
                "{\"skill\":\"ops/deploy\",\"category\":\"OTHER\",\"summary\":\"Third report should be rate limited\"}", ctx);

        assertTrue(first.isCompleted());
        assertTrue(second.isCompleted());
        assertTrue(third.isFailed());
        assertTrue(third.getResult().contains(ReportSkillPapercutTool.ERROR_RATE_LIMITED));
        assertEquals(2, submitted.get());
    }

    @Test
    void searchSkillsEmitsAttachCardForUnattachedHitAndHandlesDegradedCatalog() {
        var emittedTypes = new ArrayList<String>();
        CustomEventEmitter emitter = (type, data, card, callId) -> {
            emittedTypes.add(type);
            return null;
        };
        var ctx = ExecutionContext.builder()
                .sessionId("sess-1")
                .customVariable(CustomEventEmitter.CONTEXT_KEY, emitter)
                .build();

        var unattachedTool = SearchSkillsTool.builder()
                .backend((q, ns, limit) -> new SearchSkillsTool.SearchResponse(
                        List.of(new SearchSkillsTool.SearchHit("team/audit", "Audit flow", "DOMAIN", "team", false, List.of("tool:bash"))),
                        List.of(new SearchSkillsTool.NamespaceSummary("team", 1)),
                        false))
                .build();

        var res = unattachedTool.execute("{\"query\":\"audit\"}", ctx);
        assertTrue(res.isCompleted());
        assertTrue(res.getResult().contains("Not attached to this session"));
        assertTrue(res.getResult().contains("team/audit"));
        assertEquals(List.of("skill_attach_request"), emittedTypes);

        var degradedTool = SearchSkillsTool.builder()
                .backend((q, ns, limit) -> new SearchSkillsTool.SearchResponse(List.of(), List.of(), true))
                .build();
        var degradedRes = degradedTool.execute("{\"query\":\"audit\"}", ctx);
        assertTrue(degradedRes.isCompleted());
        assertTrue(degradedRes.getResult().contains("temporarily unavailable"));
        assertFalse(degradedRes.getResult().contains("No skills found"));
    }

    private static final class StaticSkillProvider implements SkillProvider {
        private final List<SkillMetadata> skills;

        StaticSkillProvider(List<SkillMetadata> skills) {
            this.skills = skills;
        }

        @Override
        public List<SkillMetadata> listSkills() {
            return skills;
        }

        @Override
        public String readContent(SkillMetadata skill) {
            return "";
        }

        @Override
        public String readResource(SkillMetadata skill, String resourcePath) {
            return "";
        }

        @Override
        public int priority() {
            return 10;
        }
    }
}
