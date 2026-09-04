package ai.core.server.skillhub;

import ai.core.server.domain.SkillDefinition;
import ai.core.server.domain.SkillResource;
import ai.core.server.domain.SkillSourceType;
import ai.core.server.skill.SkillArchiveBuilder;
import ai.core.server.skillhub.SkillCatalogService.CatalogSkill;
import ai.core.server.skillhub.SkillCatalogService.ScoredSkill;
import ai.core.server.skillhub.SkillCatalogService.SearchOutcome;
import ai.core.utils.JsonUtil;
import core.framework.mongo.MongoCollection;
import core.framework.web.exception.ConflictException;
import core.framework.web.exception.NotFoundException;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillHubServiceTest {
    private SkillCatalogService catalog;
    private MongoCollection<SkillDefinition> collection;
    private SkillHubService hubService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        catalog = mock(SkillCatalogService.class);
        collection = (MongoCollection<SkillDefinition>) mock(MongoCollection.class);
        hubService = new SkillHubService();
        hubService.catalog = catalog;
        hubService.accessPolicy = new SkillHubAccessPolicy();
        hubService.skillCollection = collection;
        hubService.archiveBuilder = new SkillArchiveBuilder();
    }

    @Test
    void searchMapsTwoLevelOutcome() {
        var skill = catalogSkill("stephen", "code-review");
        when(catalog.search("review", null, null, null)).thenReturn(
                new SearchOutcome(List.of(new SkillCatalogService.NamespaceSearchHit("stephen", 0, 1)),
                        List.of(new ScoredSkill(skill, 12))));

        var response = hubService.search("review", null, null, null);

        assertEquals(1, response.skills.size());
        var summary = response.skills.getFirst();
        assertEquals("stephen/code-review", summary.qualifiedName);
        assertEquals("id-1", summary.id);
        assertEquals("digest-1", summary.digest);
        assertEquals(2, summary.resourceCount);
        assertEquals(12, summary.score);
        assertEquals("stephen", response.namespaces.getFirst().namespace);
        assertEquals(1, response.namespaces.getFirst().matchedCount);
        String json = JsonUtil.toJson(response);
        assertFalse(json.contains("user_id"), "hub responses never expose user_id");
        assertFalse(json.contains("repo_config"), "hub responses never expose repo_config");
    }

    @Test
    void lookupReturnsSingleCandidate() {
        when(catalog.lookupByName("code-review")).thenReturn(List.of(catalogSkill("stephen", "code-review")));

        var response = hubService.lookup("code-review");

        assertEquals(1, response.candidates.size());
        assertEquals("stephen/code-review", response.candidates.getFirst().qualifiedName);
    }

    @Test
    void lookupThrowsWhenUnknownOrAmbiguous() {
        when(catalog.lookupByName("missing")).thenReturn(List.of());
        assertThrows(NotFoundException.class, () -> hubService.lookup("missing"));

        when(catalog.lookupByName("code-review")).thenReturn(List.of(
                catalogSkill("stephen", "code-review"), catalogSkill("anthropics", "code-review")));
        var error = assertThrows(ConflictException.class, () -> hubService.lookup("code-review"));
        assertTrue(error.getMessage().contains("stephen/code-review"));
    }

    @Test
    void showReturnsDetailWithoutResourceContentAndRecordsShow() {
        var def = definition("stephen", "code-review");
        def.content = "# Code review";
        def.resources = List.of(resource("references/style.md", "style body"));
        when(catalog.find("stephen", "code-review")).thenReturn(catalogSkill("stephen", "code-review"));
        when(collection.get("id-1")).thenReturn(Optional.of(def));

        var detail = hubService.show("stephen", "code-review");

        assertEquals("# Code review", detail.content);
        assertEquals(1, detail.resources.size());
        assertEquals("references/style.md", detail.resources.getFirst().path);
        assertEquals("style body".getBytes(StandardCharsets.UTF_8).length, detail.resources.getFirst().size);
        assertNotNull(detail.resources.getFirst().sha256);
        verify(collection).update(any(Bson.class), any(Bson.class));
    }

    @Test
    void resourceReturnsOnlyRegisteredPaths() {
        var def = definition("stephen", "code-review");
        def.content = "# Code review";
        def.resources = List.of(resource("references/style.md", "style body"));
        when(catalog.find("stephen", "code-review")).thenReturn(catalogSkill("stephen", "code-review"));
        when(collection.get("id-1")).thenReturn(Optional.of(def));

        var response = hubService.resource("stephen", "code-review", "references/style.md");
        assertEquals("style body", response.content);
        assertEquals(10, response.size);
        assertNotNull(response.sha256);

        assertThrows(NotFoundException.class, () -> hubService.resource("stephen", "code-review", "../SKILL.md"));
        assertThrows(NotFoundException.class, () -> hubService.resource("stephen", "code-review", "references/other.md"));
    }

    @Test
    void archiveBuildsZipAndRecordsPull() {
        var def = definition("stephen", "code-review");
        def.content = "# Code review";
        def.digest = "digest-1";
        when(catalog.find("stephen", "code-review")).thenReturn(catalogSkill("stephen", "code-review"));
        when(collection.get("id-1")).thenReturn(Optional.of(def));

        var bundle = hubService.archive("stephen", "code-review");

        assertNotNull(bundle.bytes());
        assertTrue(bundle.bytes().length > 0);
        assertEquals("digest-1", bundle.definition().digest);
        verify(collection).update(any(Bson.class), any(Bson.class));
    }

    @Test
    void missingSkillIsNotFound() {
        when(catalog.find("stephen", "missing")).thenReturn(null);
        assertThrows(NotFoundException.class, () -> hubService.show("stephen", "missing"));
        assertThrows(NotFoundException.class, () -> hubService.archive("stephen", "missing"));
    }

    private CatalogSkill catalogSkill(String namespace, String name) {
        return new CatalogSkill("id-1", namespace, name, namespace + "/" + name,
                "desc", "upload", List.of("web_search"), Map.of("vendor", "anthropic"),
                "digest-1", List.of("references/a.md", "scripts/run.sh"), ZonedDateTime.now());
    }

    private SkillDefinition definition(String namespace, String name) {
        var def = new SkillDefinition();
        def.id = "id-1";
        def.namespace = namespace;
        def.name = name;
        def.qualifiedName = namespace + "/" + name;
        def.description = "desc";
        def.sourceType = SkillSourceType.UPLOAD;
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
