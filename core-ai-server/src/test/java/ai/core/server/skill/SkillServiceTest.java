package ai.core.server.skill;

import ai.core.server.domain.SkillDefinition;
import ai.core.server.domain.SkillSourceType;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import core.framework.web.exception.NotFoundException;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillServiceTest {
    @Test
    void listAppliesDbPagingWithoutInMemoryFilters() {
        var service = new SkillService();
        service.skillCollection = skillCollection();

        var matchingName = skill("1", "Admin", "seo-audit", null);
        when(service.skillCollection.find(any(Query.class))).thenReturn(List.of(matchingName));

        var result = service.list(null, null, null, null, 20, 10);

        assertEquals(List.of(matchingName), result);
        var query = ArgumentCaptor.forClass(Query.class);
        verify(service.skillCollection).find(query.capture());
        assertEquals(20, query.getValue().skip);
        assertEquals(10, query.getValue().limit);
    }

    @Test
    void listFiltersAndPagesQueryInMemory() {
        var service = new SkillService();
        service.skillCollection = skillCollection();

        var matchingName = skill("1", "Admin", "seo-audit", null);
        var notMatching = skill("2", "Admin", "prompt-pack", "Prompt templates");
        var matchingDescription = skill("3", "Admin", "content-helper", "Run SEO audit checks");
        when(service.skillCollection.find(any(Query.class))).thenReturn(List.of(matchingName, notMatching, matchingDescription));

        var result = service.list(null, null, "audit", null, 1, 1);

        assertEquals(List.of(matchingDescription), result);
        verify(service.skillCollection).find(any(Query.class));
    }

    @Test
    void listFiltersCreatorInMemory() {
        var service = new SkillService();
        service.skillCollection = skillCollection();

        var matchingCreator = skill("1", "Admin", "seo-audit", null);
        matchingCreator.userId = "alice@example.com";
        var notMatching = skill("2", "Admin", "prompt-pack", null);
        notMatching.userId = "bob@example.com";
        when(service.skillCollection.find(any(Query.class))).thenReturn(List.of(matchingCreator, notMatching));

        var result = service.list(null, "ali", null, null, 0, 10);

        assertEquals(List.of(matchingCreator), result);
        verify(service.skillCollection).find(any(Query.class));
    }

    @Test
    void countFiltersQueryInMemory() {
        var service = new SkillService();
        service.skillCollection = skillCollection();

        var matchingName = skill("1", "Admin", "seo-audit", null);
        var notMatching = skill("2", "Admin", "prompt-pack", "Prompt templates");
        var matchingTool = skill("3", "Admin", "content-helper", null);
        matchingTool.allowedTools = List.of("audit-tool");
        var matchingMetadata = skill("4", "Admin", "report-helper", null);
        matchingMetadata.metadata = Map.of("category", "audit");
        when(service.skillCollection.find(any(Query.class))).thenReturn(List.of(matchingName, notMatching, matchingTool, matchingMetadata));

        var result = service.count(null, null, "audit", "metadata");

        assertEquals(3, result);
        verify(service.skillCollection).find(any(Query.class));
        verify(service.skillCollection, never()).count(any(Bson.class));
    }

    @Test
    void defaultQuerySearchesNameAndDescriptionOnly() {
        var service = new SkillService();
        service.skillCollection = skillCollection();

        var matchingDescription = skill("1", "Admin", "content-helper", "Run SEO audit checks");
        var metadataOnly = skill("2", "Admin", "prompt-pack", null);
        metadataOnly.allowedTools = List.of("audit-tool");
        when(service.skillCollection.find(any(Query.class))).thenReturn(List.of(matchingDescription, metadataOnly));

        var result = service.list(null, null, "audit", null, 0, 10);

        assertEquals(List.of(matchingDescription), result);
    }

    @Test
    void contentSearchSearchesSkillMdContentOnly() {
        var service = new SkillService();
        service.skillCollection = skillCollection();

        var contentMatch = skill("1", "Admin", "review-helper", "Review pull requests");
        contentMatch.content = "Use this skill when auditing database migrations.";
        var descriptionMatch = skill("2", "Admin", "db-helper", "Audit database migrations");
        when(service.skillCollection.find(any(Query.class))).thenReturn(List.of(contentMatch, descriptionMatch));

        var result = service.list(null, null, "auditing", "content", 0, 10);

        assertEquals(List.of(contentMatch), result);
    }

    @Test
    void getThrowsNotFoundForMissingSkill() {
        var service = new SkillService();
        service.skillCollection = skillCollection();
        when(service.skillCollection.get("missing")).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> service.get("missing"));
    }

    @SuppressWarnings("unchecked")
    private MongoCollection<SkillDefinition> skillCollection() {
        return (MongoCollection<SkillDefinition>) mock(MongoCollection.class);
    }

    private SkillDefinition skill(String id, String namespace, String name, String description) {
        var skill = new SkillDefinition();
        skill.id = id;
        skill.namespace = namespace;
        skill.name = name;
        skill.qualifiedName = namespace + "/" + name;
        skill.description = description;
        skill.sourceType = SkillSourceType.UPLOAD;
        skill.userId = namespace.toLowerCase(Locale.ROOT) + "@example.com";
        return skill;
    }
}
