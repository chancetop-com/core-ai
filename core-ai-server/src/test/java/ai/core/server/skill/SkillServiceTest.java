package ai.core.server.skill;

import ai.core.server.domain.SkillDefinition;
import ai.core.server.domain.SkillRepoConfig;
import ai.core.server.domain.SkillSourceType;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import core.framework.web.exception.ForbiddenException;
import core.framework.web.exception.NotFoundException;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillServiceTest {
    private static final String REPO_URL = "https://github.com/wuyoscar/GPT-Image2-Skill";

    @Test
    void repoRegistrationUsesInjectedSkillCollection() {
        var service = new SkillService();
        service.skillCollection = skillCollection();

        assertEquals("nextlevelbuilder", service.extractRepoOwner("https://github.com/nextlevelbuilder/ui-ux-pro-max-skill"));
    }

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

    @Test
    void resolveAccessibleSkillsAllowsExistingSkillForItsOwner() {
        var service = new SkillService();
        service.skillCollection = skillCollection();
        var owned = skill("skill-1", "Admin", "seo-audit", null);
        owned.userId = "caller-1";
        when(service.skillCollection.get("skill-1")).thenReturn(Optional.of(owned));

        var result = service.resolveAccessibleSkills(List.of("skill-1"), "caller-1");

        assertEquals(List.of("Admin/seo-audit"),
                result.stream().map(ai.core.skill.SkillMetadata::getQualifiedName).toList());
    }

    @Test
    void resolveAccessibleSkillsAllowsExistingSkillAcrossUsersAndWithoutCaller() {
        var service = new SkillService();
        service.skillCollection = skillCollection();
        var foreign = skill("foreign", "Admin", "seo-audit", null);
        foreign.userId = "owner-1";
        when(service.skillCollection.get("foreign")).thenReturn(Optional.of(foreign));

        var crossUser = service.resolveAccessibleSkills(List.of("foreign"), "caller-1");
        var callerless = service.resolveAccessibleSkills(List.of("foreign"), null);

        assertEquals(List.of("Admin/seo-audit"),
                crossUser.stream().map(ai.core.skill.SkillMetadata::getQualifiedName).toList());
        assertEquals(List.of("Admin/seo-audit"),
                callerless.stream().map(ai.core.skill.SkillMetadata::getQualifiedName).toList());
    }

    @Test
    void resolveAccessibleSkillsStillRejectsUnknownSkill() {
        var service = new SkillService();
        service.skillCollection = skillCollection();
        when(service.skillCollection.get("missing")).thenReturn(Optional.empty());

        var error = assertThrows(ForbiddenException.class,
                () -> service.resolveAccessibleSkills(List.of("missing"), "caller-1"));

        assertEquals("skill is unavailable", error.getMessage());
    }

    @Test
    void scheduledSyncSkipsTheCloneWhenTheRemoteCommitIsUnchanged() throws Exception {
        var collection = skillCollection();
        var entity = repoSkill("abc123");
        when(collection.get("repo-1")).thenReturn(Optional.of(entity));
        var manager = spy(new SkillRepoManager(collection));
        doReturn("abc123").when(manager).remoteHead(REPO_URL, "main");
        var service = service(manager, collection);

        var result = service.syncFromRepoIfChanged("repo-1");

        assertSame(entity, result);
        verify(manager, never()).cloneRepo(anyString(), anyString(), any());
        verify(collection, never()).replace(any());
    }

    @Test
    void scheduledSyncClonesWhenTheRemoteCommitMoved() throws Exception {
        var collection = skillCollection();
        var entity = repoSkill("abc123");
        when(collection.get("repo-1")).thenReturn(Optional.of(entity));
        var manager = spy(new SkillRepoManager(collection));
        doReturn("def456").when(manager).remoteHead(REPO_URL, "main");
        doNothing().when(manager).cloneRepo(anyString(), anyString(), any());
        var service = service(manager, collection);

        var error = assertThrows(RuntimeException.class, () -> service.syncFromRepoIfChanged("repo-1"));

        assertEquals("skill not found in repo after sync, name=gpt-image", error.getMessage());
        verify(manager).cloneRepo(eq(REPO_URL), eq("main"), any());
    }

    @Test
    void manualSyncClonesWithoutAskingTheRemote() throws Exception {
        var collection = skillCollection();
        var entity = repoSkill("abc123");
        when(collection.get("repo-1")).thenReturn(Optional.of(entity));
        var manager = spy(new SkillRepoManager(collection));
        doReturn("abc123").when(manager).remoteHead(anyString(), anyString());
        doNothing().when(manager).cloneRepo(anyString(), anyString(), any());
        var service = service(manager, collection);

        assertThrows(RuntimeException.class, () -> service.syncFromRepo("repo-1"));

        verify(manager).cloneRepo(eq(REPO_URL), eq("main"), any());
        verify(manager, never()).remoteHead(anyString(), anyString());
    }

    @Test
    void syncRejectsSkillsThatDoNotComeFromARepo() {
        var collection = skillCollection();
        var uploaded = skill("upload-1", "Admin", "seo-audit", null);
        when(collection.get("upload-1")).thenReturn(Optional.of(uploaded));

        var service = service(new SkillRepoManager(collection), collection);

        assertThrows(RuntimeException.class, () -> service.syncFromRepoIfChanged("upload-1"));
    }

    @SuppressWarnings("unchecked")
    private MongoCollection<SkillDefinition> skillCollection() {
        return (MongoCollection<SkillDefinition>) mock(MongoCollection.class);
    }

    private SkillService service(SkillRepoManager manager, MongoCollection<SkillDefinition> collection) {
        var service = new SkillService() {
            @Override
            SkillRepoManager repoManager() {
                return manager;
            }
        };
        service.skillCollection = collection;
        return service;
    }

    private SkillDefinition repoSkill(String lastCommitHash) {
        var skill = skill("repo-1", "wuyoscar", "gpt-image", "Generate images");
        skill.sourceType = SkillSourceType.REPO;
        var config = new SkillRepoConfig();
        config.repoUrl = REPO_URL;
        config.branch = "main";
        config.skillPath = "skills/gpt-image";
        config.lastCommitHash = lastCommitHash;
        skill.repoConfig = config;
        return skill;
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
