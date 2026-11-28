package ai.core.server.skillhub;

import ai.core.agent.ExecutionContext;
import ai.core.server.domain.SkillDefinition;
import ai.core.server.domain.SkillSourceType;
import ai.core.server.domain.SkillTier;
import ai.core.server.session.ChatMessageService;
import ai.core.server.session.SessionSkillManager;
import ai.core.server.skill.MongoSkillProvider;
import ai.core.server.skill.ServerSkillTool;
import ai.core.server.skill.SkillArchiveBuilder;
import ai.core.server.skill.SkillService;
import ai.core.server.skill.SkillStore;
import ai.core.skill.SkillMetadata;
import ai.core.skill.SkillProvider;
import ai.core.skill.SkillRegistry;
import ai.core.skill.SkillSearchScorer;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies controlled skill discovery authorization isolation, SKILL_NOT_ATTACHED guard,
 * stale-while-error catalog resilience, and CORE_TIER_BONUS calibration on SkillCatalogService.
 *
 * @author stephen
 */
class SkillControlledDiscoveryTest {
    @Test
    void controlledSearchZeroExposesImportedAndCrossNamespaceDomainAndBlocksUnattachedUseSkill() {
        var store = mock(SkillStore.class);
        var coreSkill = def("s-core", "builtin", "deploy-guide", "Deployment checklist", SkillTier.CORE, SkillSourceType.UPLOAD);
        var sameNsDomain = def("s-dom-1", "payments", "deploy-payments", "Deploy payment service", SkillTier.DOMAIN, SkillSourceType.UPLOAD);
        var otherNsDomain = def("s-dom-2", "marketing", "deploy-campaign", "Deploy marketing site", SkillTier.DOMAIN, SkillSourceType.UPLOAD);
        var importedSkill = def("s-imp", "vendor", "deploy-vendor", "Third-party deploy helper", SkillTier.IMPORTED, SkillSourceType.REPO);
        when(store.listCatalog()).thenReturn(List.of(coreSkill, sameNsDomain, otherNsDomain, importedSkill));

        var catalog = new SkillCatalogService();
        catalog.skillStore = store;

        var skillService = mock(SkillService.class);
        when(skillService.findOrNull("s-core")).thenReturn(coreSkill);
        when(skillService.findOrNull("s-dom-1")).thenReturn(sameNsDomain);
        when(skillService.findOrNull("s-dom-2")).thenReturn(otherNsDomain);
        when(skillService.findOrNull("s-imp")).thenReturn(importedSkill);

        var manager = new SessionSkillManager(skillService, mock(MongoSkillProvider.class),
                mock(SkillArchiveBuilder.class), mock(ChatMessageService.class));
        manager.setCatalogService(catalog);
        manager.setAccessPolicy(new SkillHubAccessPolicy());

        var allowedIds = Set.of("s-core");
        var scopedRegistry = new SkillRegistry();
        scopedRegistry.addProvider(new StaticSkillProvider(List.of(
                SkillMetadata.builder("deploy-guide", "Deployment checklist", null)
                        .skillId("s-core").namespace("builtin").tier("CORE").build()
        )));

        var searchTool = manager.buildSearchSkillsTool(scopedRegistry, "payments", true);
        var searchResult = searchTool.execute("{\"query\":\"deploy\"}", ExecutionContext.empty());
        assertTrue(searchResult.isCompleted());
        var text = searchResult.getResult();

        assertTrue(text.contains("builtin/deploy-guide"));
        assertTrue(text.contains("payments/deploy-payments"));
        assertFalse(text.contains("vendor/deploy-vendor"), "IMPORTED skill must have 0 exposure in search_skills");
        assertFalse(text.contains("marketing/deploy-campaign"), "Other namespace DOMAIN skill must have 0 exposure");
        assertTrue(text.contains("Not attached to this session"));
        assertEquals(Set.of("s-core"), allowedIds);

        var useSkillTool = ServerSkillTool.builder()
                .registry(scopedRegistry)
                .skillService(skillService)
                .archiveBuilder(mock(SkillArchiveBuilder.class))
                .build();
        var denied = useSkillTool.execute("{\"name\":\"payments/deploy-payments\"}", ExecutionContext.empty());
        assertTrue(denied.isFailed());
        assertTrue(denied.getResult().contains(ServerSkillTool.ERROR_NOT_ATTACHED));
    }

    @Test
    void catalogRetainsStaleSnapshotOnRefreshFailureAndMarksDegradedOnColdStartError() {
        var store = mock(SkillStore.class);
        var calls = new AtomicInteger();
        var sample = def("s-1", "team", "demo", "Demo workflow", SkillTier.DOMAIN, SkillSourceType.UPLOAD);
        when(store.listCatalog()).thenAnswer(inv -> {
            if (calls.incrementAndGet() == 1) return List.of(sample);
            throw new RuntimeException("db connection reset");
        });

        var catalog = new SkillCatalogService();
        catalog.skillStore = store;

        assertEquals(1, catalog.search("demo", null, null, 10).skills().size());
        assertFalse(catalog.isDegraded());

        catalog.invalidate();
        var afterFailure = catalog.search("demo", null, null, 10);
        assertEquals(1, afterFailure.skills().size(), "Stale-while-error must retain previous snapshot");
        assertNotNull(catalog.lastRefreshFailedAt());

        var coldStore = mock(SkillStore.class);
        when(coldStore.listCatalog()).thenThrow(new RuntimeException("cold start outage"));
        var coldCatalog = new SkillCatalogService();
        coldCatalog.skillStore = coldStore;

        var coldOutcome = coldCatalog.searchControlled("demo", "team", null, 8, null);
        assertTrue(coldOutcome.degraded());
        assertTrue(coldCatalog.isDegraded());
    }

    @Test
    void coreTierBonusCalibrationBeforeAndAfterOnCatalogSearch() {
        var store = mock(SkillStore.class);
        var domainExact = def("s-dom", "team", "sql-tune", "Optimize queries", SkillTier.DOMAIN, SkillSourceType.UPLOAD);
        var coreWeak = def("s-core", "builtin", "db-assistant", "General helper mentioning sql concepts", SkillTier.CORE, SkillSourceType.UPLOAD);
        when(store.listCatalog()).thenReturn(List.of(domainExact, coreWeak));

        var catalog = new SkillCatalogService();
        catalog.skillStore = store;

        var before = catalog.searchWithBonus("sql", null, null, 10, SkillSearchScorer.LEGACY_CORE_TIER_BONUS).skills();
        assertEquals("builtin/db-assistant", before.getFirst().skill().qualifiedName());
        assertEquals(12, before.getFirst().score());
        assertEquals("team/sql-tune", before.get(1).skill().qualifiedName());
        assertEquals(10, before.get(1).score());

        var after = catalog.searchWithBonus("sql", null, null, 10, SkillSearchScorer.CORE_TIER_BONUS).skills();
        assertEquals("team/sql-tune", after.getFirst().skill().qualifiedName());
        assertEquals(10, after.getFirst().score());
        assertEquals("builtin/db-assistant", after.get(1).skill().qualifiedName());
        assertEquals(5, after.get(1).score());
    }

    private SkillDefinition def(String id, String namespace, String name, String desc,
                                SkillTier tier, SkillSourceType sourceType) {
        var def = new SkillDefinition();
        def.id = id;
        def.namespace = namespace;
        def.name = name;
        def.qualifiedName = namespace + "/" + name;
        def.description = desc;
        def.tier = tier;
        def.sourceType = sourceType;
        def.userId = "u1";
        def.createdAt = ZonedDateTime.now();
        def.updatedAt = ZonedDateTime.now();
        return def;
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
