package ai.core.server.agenthub;

import ai.core.server.agent.AgentDependencyAccessPolicy;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentPublishedConfig;
import ai.core.server.domain.AgentSandboxConfig;
import ai.core.server.domain.AgentStatus;
import ai.core.server.domain.DefinitionType;
import ai.core.server.domain.ToolRef;
import ai.core.server.skill.SkillService;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The catalog is the only surface that exposes agents to other callers, so its rules are asserted
 * here: what is visible (published / own draft / system default), what is runnable, which config a
 * run would use, and that the summary never carries a system prompt.
 *
 * @author stephen
 */
class AgentCatalogServiceTest {
    private static final String OWNER = "user-owner";
    private static final int LIST_LIMIT = 50;
    private static final String OTHER = "user-other";

    private MongoCollection<AgentDefinition> collection;
    private SkillService skillService;
    private AgentCatalogService catalog;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        collection = (MongoCollection<AgentDefinition>) mock(MongoCollection.class);
        skillService = mock(SkillService.class);
        when(skillService.batchResolve(any())).thenReturn(Map.of());
        catalog = new AgentCatalogService();
        catalog.agentDefinitionCollection = collection;
        catalog.skillService = skillService;
    }

    @Test
    void refreshProjectsAwayTheSystemPromptAndNeverAsksSkillsWithoutIds() {
        stubFind(publishedAgent("agent-1", OWNER, "reviewer"));

        var agents = search(OTHER, null, null, null);

        var queryCaptor = ArgumentCaptor.forClass(Query.class);
        verify(collection).find(queryCaptor.capture());
        assertNotNull(queryCaptor.getValue().projection, "catalog query must project away system_prompt");
        assertEquals(1, agents.size());
        verify(skillService, never()).batchResolve(any());
    }

    @Test
    void publishedAgentsAreVisibleToEveryoneAndDraftsOnlyToTheirOwner() {
        stubFind(publishedAgent("agent-1", OWNER, "reviewer"), draftAgent("agent-2", OWNER, "wip"));

        assertEquals(List.of("reviewer"), names(search(OTHER, null, null, null)));
        assertEquals(List.of("reviewer", "wip"), names(search(OWNER, null, null, null)));
    }

    @Test
    void visibleButNotRunnableAgentIsNotListed() {
        var systemDefaultDraft = draftAgent("agent-1", OWNER, "default agent");
        systemDefaultDraft.systemDefault = Boolean.TRUE;
        var publishedWithoutSnapshot = publishedAgent("agent-2", OWNER, "half published");
        publishedWithoutSnapshot.publishedConfig = null;
        stubFind(systemDefaultDraft, publishedWithoutSnapshot);

        assertEquals(List.of(), names(search(OTHER, null, null, null)));
    }

    @Test
    void typeFilterKeepsOnlyTheRequestedDefinitionType() {
        stubFind(publishedAgent("agent-1", OWNER, "reviewer"), publishedLlmCall("agent-2", OWNER, "summarizer"));

        assertEquals(List.of("summarizer"), names(search(OTHER, null, "llm_call", null)));
        assertEquals(List.of("reviewer"), names(search(OTHER, null, "agent", null)));
    }

    @Test
    void sourceFilterIsServerOnlyUntilExternalAgentsShip() {
        stubFind(publishedAgent("agent-1", OWNER, "reviewer"));

        assertEquals(List.of(), names(search(OTHER, null, null, "external")));
        assertEquals(List.of("reviewer"), names(search(OTHER, null, null, "server")));
    }

    @Test
    void lookupIsCaseAndSpaceInsensitiveAndKeepsEveryCandidate() {
        stubFind(publishedAgent("agent-1", "alice", "Code Reviewer"), publishedAgent("agent-2", "bob", "code reviewer"));

        assertEquals(List.of("agent-1", "agent-2"), ids(catalog.lookup(OTHER, " Code Reviewer ")));
        assertEquals(List.of(), catalog.lookup(OTHER, " "));
        assertEquals(List.of(), catalog.lookup(OTHER, null));
    }

    @Test
    void lookupNeverReturnsAnAgentTheCallerCouldNotRun() {
        stubFind(draftAgent("agent-1", "alice", "reviewer"));

        assertEquals(List.of(), catalog.lookup(OTHER, "reviewer"));
        assertEquals(List.of("agent-1"), ids(catalog.lookup("alice", "reviewer")));
    }

    @Test
    void everyQueryTokenMustHitNameDescriptionOrSkillName() {
        var agent = publishedAgent("agent-1", OWNER, "issue triage");
        agent.skillIds = List.of("skill-1");
        agent.publishedConfig.skillIds = List.of("skill-1");
        stubFind(agent);
        when(skillService.batchResolve(Set.of("skill-1"))).thenReturn(Map.of("skill-1", "jira-issues"));

        assertEquals(List.of("agent-1"), ids(search(OTHER, "jira", null, null)));
        assertEquals(List.of("agent-1"), ids(search(OTHER, "triage jira", null, null)));
        assertEquals(List.of(), ids(search(OTHER, "triage kubernetes", null, null)));
    }

    @Test
    void capabilityIsTheLiveDraftForTheOwnerAndTheSnapshotForEveryoneElse() {
        var agent = publishedAgent("agent-1", OWNER, "reviewer");
        agent.tools = List.of(tool(), tool());
        agent.publishedConfig.tools = List.of(tool());
        agent.publishedConfig.sandboxConfig = new AgentSandboxConfig();
        agent.inputTemplate = "the draft hint";
        stubFind(agent);

        var catalogAgent = catalog.find(OWNER, "agent-1");
        assertEquals(2, catalog.capabilityFor(catalogAgent, OWNER).toolCount());
        assertEquals("the draft hint", catalog.capabilityFor(catalogAgent, OWNER).inputHint());

        assertEquals(1, catalog.capabilityFor(catalogAgent, OTHER).toolCount());
        assertTrue(catalog.capabilityFor(catalogAgent, OTHER).hasSandbox());
        assertNull(catalog.capabilityFor(catalogAgent, OTHER).inputHint());
    }

    @Test
    void llmCallAlwaysRunsItsPublishedSnapshotEvenForItsOwner() {
        var llmCall = publishedLlmCall("agent-1", OWNER, "summarizer");
        llmCall.tools = List.of(tool(), tool());
        llmCall.publishedConfig.tools = List.of(tool());
        stubFind(llmCall);

        var catalogAgent = catalog.find(OWNER, "agent-1");
        assertEquals(1, catalog.capabilityFor(catalogAgent, OWNER).toolCount());
    }

    @Test
    void unvalidatedPublishedSkillsMakeTheSnapshotUnusableForEveryoneElse() {
        var agent = publishedAgent("agent-1", OWNER, "reviewer");
        agent.publishedConfig.skillIds = List.of("skill-1");
        agent.publishedConfig.skillValidationVersion = null;
        agent.tools = List.of(tool());
        stubFind(agent);

        assertNull(catalog.find(OTHER, "agent-1"), "an unvalidated snapshot is not runnable by others");
        assertEquals(1, catalog.capabilityFor(catalog.find(OWNER, "agent-1"), OWNER).toolCount(),
                "the owner still sees the live draft");
    }

    @Test
    void listingPutsTheSystemDefaultFirstThenMostRecentlyPublished() {
        var older = publishedAgent("agent-1", OWNER, "older");
        older.publishedAt = ZonedDateTime.parse("2026-09-01T00:00:00Z");
        var newer = publishedAgent("agent-2", OWNER, "newer");
        newer.publishedAt = ZonedDateTime.parse("2026-09-05T00:00:00Z");
        newer.systemDefault = Boolean.TRUE;
        stubFind(older, newer);

        assertEquals(List.of("agent-2", "agent-1"), ids(search(OTHER, null, null, null)));
    }

    @Test
    void blankQueryListsAtMostTheRequestedLimit() {
        stubFind(publishedAgent("agent-1", OWNER, "a"), publishedAgent("agent-2", OWNER, "b"),
                publishedAgent("agent-3", OWNER, "c"));

        assertEquals(2, catalog.search(OTHER, "  ", null, null, 2).size());
        assertEquals(3, catalog.search(OTHER, null, null, "server", 500).size(), "limit is capped, not rejected");
    }

    @Test
    void refreshFailureDegradesToAnEmptyCatalogInsteadOfFailingTheRequest() {
        when(collection.find(any(Query.class))).thenThrow(new IllegalStateException("mongo down"));

        assertEquals(List.of(), search(OTHER, null, null, null));
    }

    @Test
    void snapshotIsServedUntilItIsRefreshedOrInvalidated() {
        stubFind(publishedAgent("agent-1", OWNER, "reviewer"));
        assertEquals(List.of("agent-1"), ids(search(OTHER, null, null, null)));

        stubFind(publishedAgent("agent-2", OWNER, "writer"));
        assertEquals(List.of("agent-1"), ids(search(OTHER, null, null, null)), "served from the snapshot");

        catalog.refresh();
        assertEquals(List.of("agent-2"), ids(search(OTHER, null, null, null)));

        catalog.invalidate();
        stubFind(publishedAgent("agent-1", OWNER, "reviewer"));
        assertEquals(List.of("agent-1"), ids(search(OTHER, null, null, null)), "reloaded after invalidate");
    }

    private List<AgentCatalogService.CatalogAgent> search(String userId, String query, String type, String source) {
        return catalog.search(userId, query, type, source, LIST_LIMIT);
    }

    private void stubFind(AgentDefinition... definitions) {
        when(collection.find(any(Query.class))).thenReturn(List.of(definitions));
    }

    private List<String> names(List<AgentCatalogService.CatalogAgent> agents) {
        return agents.stream().map(AgentCatalogService.CatalogAgent::name).toList();
    }

    private List<String> ids(List<AgentCatalogService.CatalogAgent> agents) {
        return agents.stream().map(AgentCatalogService.CatalogAgent::id).toList();
    }

    private AgentDefinition publishedAgent(String id, String userId, String name) {
        var definition = definition(id, userId, name, DefinitionType.AGENT, AgentStatus.PUBLISHED);
        definition.publishedConfig = config();
        return definition;
    }

    private AgentDefinition publishedLlmCall(String id, String userId, String name) {
        var definition = definition(id, userId, name, DefinitionType.LLM_CALL, AgentStatus.PUBLISHED);
        definition.publishedConfig = config();
        return definition;
    }

    private AgentDefinition draftAgent(String id, String userId, String name) {
        return definition(id, userId, name, DefinitionType.AGENT, AgentStatus.DRAFT);
    }

    private AgentDefinition definition(String id, String userId, String name, DefinitionType type, AgentStatus status) {
        var definition = new AgentDefinition();
        definition.id = id;
        definition.userId = userId;
        definition.name = name;
        definition.description = "does " + name;
        definition.type = type;
        definition.status = status;
        definition.createdAt = ZonedDateTime.parse("2026-09-01T00:00:00Z");
        definition.updatedAt = definition.createdAt;
        return definition;
    }

    private AgentPublishedConfig config() {
        var config = new AgentPublishedConfig();
        config.skillValidationVersion = AgentDependencyAccessPolicy.CURRENT_SKILL_VALIDATION_VERSION;
        return config;
    }

    private ToolRef tool() {
        var ref = new ToolRef();
        ref.id = "builtin:shell";
        return ref;
    }
}
