package ai.core.server.skillhub;

import ai.core.server.domain.SkillDefinition;
import ai.core.server.domain.SkillResource;
import ai.core.server.domain.SkillSourceType;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillCatalogServiceTest {
    private MongoCollection<SkillDefinition> collection;
    private SkillCatalogService catalog;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        collection = (MongoCollection<SkillDefinition>) mock(MongoCollection.class);
        catalog = new SkillCatalogService();
        catalog.skillCollection = collection;
    }

    @Test
    void refreshProjectsAwayContentAndSnapshotCarriesMetadataOnly() {
        var def = definition("stephen", "code-review", "Structured code review");
        def.content = "full SKILL.md body";
        def.resources = List.of(resource("scripts/check.sh", "#!/bin/sh"), resource("references/style.md", "style"));
        stubFind(def);

        var outcome = catalog.search("code-review", null, null, null);

        var queryCaptor = ArgumentCaptor.forClass(Query.class);
        verify(collection).find(queryCaptor.capture());
        assertNotNull(queryCaptor.getValue().projection, "catalog query must exclude content");
        assertEquals(1, outcome.skills().size());
        var skill = outcome.skills().getFirst().skill();
        assertEquals(2, skill.resourcePaths().size());
        assertEquals(List.of("references/style.md", "scripts/check.sh"), skill.resourcePaths(), "paths sorted");
        assertFalse(hasField("content"), "CatalogSkill record carries no content field by construction");
    }

    @Test
    void searchFindsExactNameMatchWithBonus() {
        var def = definition("stephen", "code-review", "Structured code review checklist");
        stubFind(def);

        var outcome = catalog.search("code-review", null, null, null);

        assertEquals(1, outcome.skills().size());
        assertEquals(1, outcome.namespaces().size());
        assertEquals("stephen", outcome.namespaces().getFirst().namespace());
        assertTrue(outcome.skills().getFirst().score() >= 100, "exact name match gets the +100 bonus");
    }

    @Test
    void allowedToolsAndMetadataHitsAreScoredAndKeepTheSkill() {
        var def = definition("stephen", "browser-automation", "Drive a headless browser");
        def.allowedTools = List.of("web_search", "browser-use");
        def.metadata = Map.of("vendor", "anthropic");
        stubFind(def);

        var kept = catalog.search("web_search", null, null, null);
        assertEquals(1, kept.skills().size(), "token hitting allowed-tools keeps the skill");
        assertEquals(5, kept.skills().getFirst().score(), "allowed-tools bonus only (no standard field hit)");

        var metadataHit = catalog.search("anthropic", null, null, null);
        assertEquals(1, metadataHit.skills().size(), "token hitting metadata value keeps the skill");
        assertEquals(5, metadataHit.skills().getFirst().score(), "metadata bonus only");
    }

    @Test
    void searchDropsSkillsWhenAnyTokenMisses() {
        var def = definition("stephen", "code-review", "Structured code review checklist");
        stubFind(def);

        assertEquals(1, catalog.search("code review", null, null, null).skills().size());
        assertTrue(catalog.search("code nonsense", null, null, null).skills().isEmpty());
        assertTrue(catalog.search("code nonsense", null, null, null).namespaces().isEmpty());
    }

    @Test
    void brandNamespaceMatchesRankAboveDescriptionHits() {
        stubFind(
                definition("stephen", "seo-audit", "Audit a site for search engine visibility"),
                definition("search", "seo-keywords", "Keyword research"));

        var outcome = catalog.search("search", null, null, null);

        assertEquals(2, outcome.skills().size());
        assertEquals("search/seo-keywords", outcome.skills().get(0).skill().qualifiedName());
        assertTrue(outcome.namespaces().getFirst().score() > 0, "namespace brand layer ranks first");
    }

    @Test
    void searchCapsSkillsPerNamespaceAndNamespaceFilterDrillsDown() {
        stubFind(
                definition("big", "get-a", "alpha tool"),
                definition("big", "get-b", "beta tool"),
                definition("big", "get-c", "gamma tool"),
                definition("big", "get-d", "delta tool"),
                definition("big", "get-e", "epsilon tool"));

        var outcome = catalog.search("get", null, null, 200);

        assertEquals(1, outcome.namespaces().size());
        assertEquals(5, outcome.namespaces().getFirst().matchedCount(), "matched count reports the full set");
        assertEquals(3, outcome.skills().size(), "at most 3 skills per namespace in query mode");
        var drilled = catalog.search("get", "big", null, null).skills();
        assertEquals(5, drilled.size(), "namespace drill-down ignores the per-namespace cap");
    }

    @Test
    void sourceTypeFilterNarrowsListing() {
        var upload = definition("stephen", "alpha", "uploaded skill");
        upload.sourceType = SkillSourceType.UPLOAD;
        var repo = definition("stephen", "beta", "repo skill");
        repo.sourceType = SkillSourceType.REPO;
        stubFind(upload, repo);

        var uploaded = catalog.search(null, null, "upload", 10).skills();
        assertEquals(1, uploaded.size());
        assertEquals("stephen/alpha", uploaded.getFirst().skill().qualifiedName());
    }

    @Test
    void lookupByBareNameFindsEveryNamespace() {
        stubFind(
                definition("stephen", "code-review", "review a"),
                definition("anthropics", "code-review", "review b"));

        var candidates = catalog.lookupByName("code-review");
        assertEquals(2, candidates.size());
        assertEquals("anthropics/code-review", candidates.getFirst().qualifiedName(), "sorted by qualified name");
        assertTrue(catalog.lookupByName("missing").isEmpty());
    }

    @Test
    void querylessListingIsFlatAndSorted() {
        stubFind(
                definition("stephen", "zeta", "last"),
                definition("anthropics", "alpha", "first"));

        var outcome = catalog.search(null, null, null, 10);
        assertEquals(2, outcome.skills().size());
        assertEquals("anthropics/alpha", outcome.skills().getFirst().skill().qualifiedName());
        assertTrue(outcome.namespaces().isEmpty(), "listing mode has no namespace level");
    }

    @Test
    void searchAppliesLimit() {
        stubFind(definition("stephen", "alpha", "a"), definition("stephen", "beta", "b"));
        assertEquals(1, catalog.search(null, null, null, 1).skills().size());
    }

    @Test
    void invalidateForcesOnDemandReload() {
        var def = definition("stephen", "code-review", "review");
        stubFind(def);
        assertEquals(1, catalog.search(null, null, null, 10).skills().size());
        verify(collection, org.mockito.Mockito.times(1)).find(any(Query.class));

        catalog.invalidate();
        assertNotNull(catalog.find("stephen", "code-review"));
        verify(collection, org.mockito.Mockito.times(2)).find(any(Query.class));
    }

    @Test
    void missingSkillIsNotVisible() {
        stubFind();
        assertNull(catalog.find("stephen", "code-review"));
        assertTrue(catalog.search(null, null, null, 10).skills().isEmpty());
    }

    private boolean hasField(String name) {
        return List.of(SkillCatalogService.CatalogSkill.class.getRecordComponents()).stream()
                .anyMatch(component -> component.getName().equals(name));
    }

    private void stubFind(SkillDefinition... defs) {
        when(collection.find(any(Query.class))).thenReturn(List.of(defs));
    }

    private SkillDefinition definition(String namespace, String name, String description) {
        var def = new SkillDefinition();
        def.id = namespace + "-" + name;
        def.namespace = namespace;
        def.name = name;
        def.qualifiedName = namespace + "/" + name;
        def.description = description;
        def.sourceType = SkillSourceType.UPLOAD;
        def.content = "body";
        def.userId = "u1";
        def.createdAt = ZonedDateTime.now();
        def.updatedAt = ZonedDateTime.now();
        return def;
    }

    private SkillResource resource(String path, String content) {
        var resource = new SkillResource();
        resource.path = path;
        resource.content = content;
        return resource;
    }
}
