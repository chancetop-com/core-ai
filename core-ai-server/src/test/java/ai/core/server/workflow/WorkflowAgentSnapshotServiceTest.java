package ai.core.server.workflow;

import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentPublishedConfig;
import ai.core.server.domain.AgentSnapshotSource;
import ai.core.server.domain.AgentStatus;
import ai.core.server.domain.DefinitionType;
import ai.core.server.domain.ToolRef;
import ai.core.server.domain.ToolSourceType;
import ai.core.server.skill.SkillService;
import ai.core.server.workflow.engine.WorkflowGraph;
import ai.core.skill.SkillMetadata;
import core.framework.json.JSON;
import core.framework.mongo.MongoCollection;
import core.framework.web.exception.ForbiddenException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WorkflowAgentSnapshotServiceTest {
    @SuppressWarnings("unchecked")
    private final MongoCollection<AgentDefinition> collection = mock(MongoCollection.class);
    private final SkillService skillService = mock(SkillService.class);
    private final WorkflowAgentSnapshotService service = new WorkflowAgentSnapshotService();

    @BeforeEach
    void setUp() {
        service.agentDefinitionCollection = collection;
        service.skillService = skillService;
    }

    @Test
    void ownedDraftCapturesCurrentEditableConfig() {
        AgentDefinition agent = agent("a1", "owner", AgentStatus.DRAFT, DefinitionType.AGENT);
        agent.systemPrompt = "current editable";
        agent.skillIds = List.of("owner-skill");
        when(collection.get("a1")).thenReturn(Optional.of(agent));
        when(skillService.resolveAccessibleSkills(List.of("owner-skill"), "owner"))
            .thenReturn(List.of(SkillMetadata.builder("Owner skill", "", "owner/skill").build()));

        Capture capture = capture(graph("n1", "AGENT", "a1"), "owner");

        assertTrue(capture.errors.isEmpty());
        assertEquals("current editable", snapshot(capture, "n1").systemPrompt);
        assertEquals(List.of("owner-skill"), snapshot(capture, "n1").skillIds);
        assertEquals(1, snapshot(capture, "n1").skillValidationVersion);
        assertEquals("OWNED_EDITABLE", source(capture, "n1").sourceKind);
        verify(skillService).resolveAccessibleSkills(List.of("owner-skill"), "owner");
    }

    @Test
    void previewCaptureRejectsUnknownSkillInOwnedEditableAgent() {
        assertUnavailableOwnedEditableSkill("unknown-skill");
    }

    @Test
    void ownedPublishedAgentCapturesCurrentEditableConfigBeforeOldPublishedConfig() {
        AgentDefinition agent = agent("a2", "owner", AgentStatus.PUBLISHED, DefinitionType.AGENT);
        agent.systemPrompt = "new editable value";
        agent.publishedConfig = config("old public value");
        when(collection.get("a2")).thenReturn(Optional.of(agent));

        Capture capture = capture(graph("n2", "AGENT", "a2"), "owner");

        assertTrue(capture.errors.isEmpty());
        assertEquals("new editable value", snapshot(capture, "n2").systemPrompt);
        assertEquals("OWNED_EDITABLE", source(capture, "n2").sourceKind);
    }

    @Test
    void otherUsersPublishedLlmCapturesPublishedConfig() {
        AgentDefinition agent = agent("a3", "other", AgentStatus.PUBLISHED, DefinitionType.LLM_CALL);
        agent.systemPrompt = "unpublished edit";
        agent.skillIds = List.of("editable-private-skill");
        agent.publishedConfig = config("published value");
        agent.publishedConfig.skillIds = List.of("frozen-published-skill");
        agent.publishedConfig.skillValidationVersion = 1;
        when(collection.get("a3")).thenReturn(Optional.of(agent));

        Capture capture = capture(graph("n3", "LLM", "a3"), "owner");

        assertTrue(capture.errors.isEmpty());
        assertEquals("published value", snapshot(capture, "n3").systemPrompt);
        assertEquals(List.of("frozen-published-skill"), snapshot(capture, "n3").skillIds);
        assertEquals("PUBLISHED", source(capture, "n3").sourceKind);
        verifyNoInteractions(skillService);
    }

    @Test
    void rejectsLegacyPublishedSnapshotWithUnvalidatedSkills() {
        AgentDefinition agent = agent("legacy", "other", AgentStatus.PUBLISHED, DefinitionType.AGENT);
        agent.publishedConfig = config("legacy published value");
        agent.publishedConfig.skillIds = List.of("historical-skill");
        when(collection.get(agent.id)).thenReturn(Optional.of(agent));

        Capture capture = capture(graph("n1", "AGENT", agent.id), "owner");

        assertEquals(List.of("node n1 references an agent/LLM definition you cannot access: legacy"),
            capture.errors);
        assertTrue(capture.snapshots.isEmpty());
        verifyNoInteractions(skillService);
    }

    @Test
    void systemDefaultOwnedByCallerStillCapturesPublishedConfig() {
        AgentDefinition agent = agent("system", "owner", AgentStatus.PUBLISHED, DefinitionType.AGENT);
        agent.systemDefault = Boolean.TRUE;
        agent.systemPrompt = "system draft edit";
        agent.publishedConfig = config("system published value");
        when(collection.get("system")).thenReturn(Optional.of(agent));

        Capture capture = capture(graph("n4", "AGENT", "system"), "owner");

        assertTrue(capture.errors.isEmpty());
        assertEquals("system published value", snapshot(capture, "n4").systemPrompt);
        assertEquals("PUBLISHED", source(capture, "n4").sourceKind);
    }

    @Test
    void rejectsOtherUsersDraft() {
        AgentDefinition agent = agent("other-draft", "other", AgentStatus.DRAFT, DefinitionType.AGENT);
        when(collection.get(agent.id)).thenReturn(Optional.of(agent));

        Capture capture = capture(graph("n1", "AGENT", agent.id), "owner");

        assertEquals(List.of("node n1 references an agent/LLM definition you cannot access: other-draft"), capture.errors);
        assertTrue(capture.snapshots.isEmpty());
        assertTrue(capture.sources.isEmpty());
    }

    @Test
    void rejectsMissingDefinition() {
        when(collection.get("missing")).thenReturn(Optional.empty());

        Capture capture = capture(graph("n1", "AGENT", "missing"), "owner");

        assertEquals(List.of("node n1 references an unknown agent/LLM definition: missing"), capture.errors);
    }

    @Test
    void rejectsDefinitionWithWrongType() {
        AgentDefinition agent = agent("wrong-type", "owner", AgentStatus.DRAFT, DefinitionType.LLM_CALL);
        when(collection.get(agent.id)).thenReturn(Optional.of(agent));

        Capture capture = capture(graph("n1", "AGENT", agent.id), "owner");

        assertEquals(List.of("node n1 references an agent with the wrong type: wrong-type"), capture.errors);
    }

    @Test
    void rejectsAgentNodeWithoutAgentId() {
        WorkflowGraph graph = WorkflowGraphParser.parse("""
            {"nodes":[{"id":"n1","type":"AGENT","config":{}}],"edges":[]}
            """);

        Capture capture = capture(graph, "owner");

        assertEquals(List.of("node n1 is missing agent_id"), capture.errors);
    }

    @Test
    void ownedEditableAgentRejectsMissingSubAgentDependency() {
        AgentDefinition top = agent("top", "owner", AgentStatus.DRAFT, DefinitionType.AGENT);
        top.subAgentIds = List.of("missing-sub");
        when(collection.get("top")).thenReturn(Optional.of(top));
        when(collection.get("missing-sub")).thenReturn(Optional.empty());

        Capture capture = capture(graph("n1", "AGENT", "top"), "owner");

        assertEquals(List.of("node n1 references sub-agent without a usable published config: missing-sub"), capture.errors);
        assertTrue(capture.snapshots.isEmpty());
        assertTrue(capture.sources.isEmpty());
    }

    @Test
    void ownedEditableAgentRejectsUnpublishedSubAgentDependency() {
        AgentDefinition top = agent("top", "owner", AgentStatus.DRAFT, DefinitionType.AGENT);
        top.subAgentIds = List.of("private-sub");
        AgentDefinition sub = agent("private-sub", "owner", AgentStatus.DRAFT, DefinitionType.AGENT);
        when(collection.get("top")).thenReturn(Optional.of(top));
        when(collection.get("private-sub")).thenReturn(Optional.of(sub));

        Capture capture = capture(graph("n1", "AGENT", "top"), "owner");

        assertEquals(List.of("node n1 references sub-agent without a usable published config: private-sub"), capture.errors);
        assertTrue(capture.snapshots.isEmpty());
        assertTrue(capture.sources.isEmpty());
    }

    @Test
    void ownedEditableAgentRejectsPublishedSubAgentWithNullConfig() {
        AgentDefinition top = agent("top", "owner", AgentStatus.DRAFT, DefinitionType.AGENT);
        top.subAgentIds = List.of("broken-sub");
        AgentDefinition sub = agent("broken-sub", "other", AgentStatus.PUBLISHED, DefinitionType.AGENT);
        when(collection.get("top")).thenReturn(Optional.of(top));
        when(collection.get("broken-sub")).thenReturn(Optional.of(sub));

        Capture capture = capture(graph("n1", "AGENT", "top"), "owner");

        assertEquals(List.of("node n1 references sub-agent without a usable published config: broken-sub"), capture.errors);
        assertTrue(capture.snapshots.isEmpty());
        assertTrue(capture.sources.isEmpty());
    }

    @Test
    void ownedEditableAgentKeepsOnlyPublishedSubAgentIdsWithoutEmbeddingPrivateDependency() {
        AgentDefinition top = agent("top", "owner", AgentStatus.DRAFT, DefinitionType.AGENT);
        top.subAgentIds = List.of("published-sub");
        AgentDefinition sub = agent("published-sub", "other", AgentStatus.PUBLISHED, DefinitionType.AGENT);
        sub.publishedConfig = config("sub public value");
        when(collection.get("top")).thenReturn(Optional.of(top));
        when(collection.get("published-sub")).thenReturn(Optional.of(sub));

        Capture capture = capture(graph("n1", "AGENT", "top"), "owner");

        assertTrue(capture.errors.isEmpty());
        assertEquals(List.of("published-sub"), snapshot(capture, "n1").subAgentIds);
        assertEquals(1, capture.snapshots.size());
        assertFalse(capture.snapshots.containsKey("published-sub"));
    }

    @Test
    void ownedAgentRejectsCallerOwnedLlmCallToolWithoutSnapshotOrProvenance() {
        AgentDefinition top = agent("top", "owner", AgentStatus.DRAFT, DefinitionType.AGENT);
        top.tools = List.of(ToolRef.fromLegacyToolId("llm-call:own-llm"));
        AgentDefinition target = agent("own-llm", "owner", AgentStatus.DRAFT, DefinitionType.LLM_CALL);
        when(collection.get("top")).thenReturn(Optional.of(top));
        when(collection.get("own-llm")).thenReturn(Optional.of(target));

        assertLlmCallToolRejected(capture(graph("n1", "AGENT", "top"), "owner"));
    }

    @Test
    void ownedAgentRejectsPublishedLlmCallToolWithoutSnapshotOrProvenance() {
        AgentDefinition top = agent("top", "owner", AgentStatus.DRAFT, DefinitionType.AGENT);
        top.tools = List.of(ToolRef.fromLegacyToolId("llm-call:published-llm"));
        AgentDefinition target = agent("published-llm", "other", AgentStatus.PUBLISHED, DefinitionType.LLM_CALL);
        target.publishedConfig = config("public target");
        when(collection.get("top")).thenReturn(Optional.of(top));
        when(collection.get("published-llm")).thenReturn(Optional.of(target));

        assertLlmCallToolRejected(capture(graph("n1", "AGENT", "top"), "owner"));
    }

    @Test
    void publishedAgentSnapshotSourceRejectsLlmCallToolWithoutSnapshotOrProvenance() {
        AgentDefinition top = agent("shared-top", "other", AgentStatus.PUBLISHED, DefinitionType.AGENT);
        top.publishedConfig = config("public top");
        top.publishedConfig.tools = List.of(ToolRef.of("llm-call:other-private", ToolSourceType.LLM_CALL));
        when(collection.get("shared-top")).thenReturn(Optional.of(top));

        assertLlmCallToolRejected(capture(graph("n1", "AGENT", "shared-top"), "owner"));
    }

    @Test
    void publishedAgentSnapshotSourceRejectsDraftSubAgentDependency() {
        AgentDefinition top = agent("shared-top", "other", AgentStatus.PUBLISHED, DefinitionType.AGENT);
        top.publishedConfig = config("public top");
        top.publishedConfig.subAgentIds = List.of("draft-sub");
        AgentDefinition sub = agent("draft-sub", "other", AgentStatus.DRAFT, DefinitionType.AGENT);
        when(collection.get("shared-top")).thenReturn(Optional.of(top));
        when(collection.get("draft-sub")).thenReturn(Optional.of(sub));

        Capture capture = capture(graph("n1", "AGENT", "shared-top"), "owner");

        assertEquals(List.of("node n1 references sub-agent without a usable published config: draft-sub"),
            capture.errors);
        assertTrue(capture.snapshots.isEmpty());
        assertTrue(capture.sources.isEmpty());
    }

    @Test
    void publishedAgentSnapshotSourceRejectsPublishedLlmCallAsSubAgent() {
        AgentDefinition top = agent("shared-top", "other", AgentStatus.PUBLISHED, DefinitionType.AGENT);
        top.publishedConfig = config("public top");
        top.publishedConfig.subAgentIds = List.of("published-llm");
        AgentDefinition sub = agent("published-llm", "other", AgentStatus.PUBLISHED, DefinitionType.LLM_CALL);
        sub.publishedConfig = config("public llm");
        when(collection.get("shared-top")).thenReturn(Optional.of(top));
        when(collection.get("published-llm")).thenReturn(Optional.of(sub));

        Capture capture = capture(graph("n1", "AGENT", "shared-top"), "owner");

        assertEquals(List.of("node n1 references sub-agent without a usable published config: published-llm"),
            capture.errors);
        assertTrue(capture.snapshots.isEmpty());
        assertTrue(capture.sources.isEmpty());
    }

    @Test
    void ownedEditableAgentRejectsPublishedLlmCallDefinitionAsSubAgent() {
        AgentDefinition top = agent("top", "owner", AgentStatus.DRAFT, DefinitionType.AGENT);
        top.subAgentIds = List.of("published-llm");
        AgentDefinition sub = agent("published-llm", "other", AgentStatus.PUBLISHED, DefinitionType.LLM_CALL);
        sub.publishedConfig = config("public llm config");
        when(collection.get("top")).thenReturn(Optional.of(top));
        when(collection.get("published-llm")).thenReturn(Optional.of(sub));

        Capture capture = capture(graph("n1", "AGENT", "top"), "owner");

        assertEquals(List.of("node n1 references sub-agent without a usable published config: published-llm"),
            capture.errors);
        assertTrue(capture.snapshots.isEmpty());
        assertTrue(capture.sources.isEmpty());
    }

    @Test
    void captureDeepCopiesEditableConfigWithoutMutatingSourceDefinition() {
        AgentDefinition agent = agent("copy", "owner", AgentStatus.DRAFT, DefinitionType.AGENT);
        var sourceVariables = new LinkedHashMap<>(Map.of("locale", "en"));
        var sourceSkills = new ArrayList<>(List.of("skill-1"));
        agent.variables = sourceVariables;
        agent.skillIds = sourceSkills;
        when(collection.get("copy")).thenReturn(Optional.of(agent));
        when(skillService.resolveAccessibleSkills(List.of("skill-1"), "owner"))
            .thenReturn(List.of(SkillMetadata.builder("Skill one", "", "owner/skill-1").build()));

        Capture capture = capture(graph("n1", "AGENT", "copy"), "owner");
        AgentPublishedConfig frozen = snapshot(capture, "n1");
        frozen.variables.put("locale", "changed");
        frozen.skillIds.add("skill-2");

        assertEquals(Map.of("locale", "en"), sourceVariables);
        assertEquals(List.of("skill-1"), sourceSkills);
        assertEquals(Map.of("locale", "en"), agent.variables);
        assertEquals(List.of("skill-1"), agent.skillIds);
    }

    @Test
    void captureRecordsAgentAndSourceTimestamps() {
        AgentDefinition agent = agent("timestamped", "owner", AgentStatus.DRAFT, DefinitionType.AGENT);
        agent.updatedAt = ZonedDateTime.parse("2026-08-04T15:16:17+08:00[Asia/Shanghai]");
        when(collection.get("timestamped")).thenReturn(Optional.of(agent));
        ZonedDateTime before = ZonedDateTime.now();

        Capture capture = capture(graph("n1", "AGENT", "timestamped"), "owner");
        ZonedDateTime after = ZonedDateTime.now();
        AgentSnapshotSource source = source(capture, "n1");

        assertEquals("timestamped", source.agentId);
        assertEquals(agent.updatedAt.toInstant(), source.sourceUpdatedAt.toInstant());
        assertNotNull(source.capturedAt);
        assertFalse(source.capturedAt.isBefore(before));
        assertFalse(source.capturedAt.isAfter(after));
    }

    @Test
    @SuppressWarnings("unchecked")
    void captureSerializesStableSnakeCaseProvenanceKeys() {
        AgentDefinition agent = agent("provenance", "owner", AgentStatus.DRAFT, DefinitionType.AGENT);
        when(collection.get("provenance")).thenReturn(Optional.of(agent));

        Capture capture = capture(graph("n1", "AGENT", "provenance"), "owner");
        Map<String, Object> raw = JSON.fromJSON(Map.class, capture.sources.get("n1"));

        assertEquals(Set.of("agent_id", "source_kind", "source_updated_at", "captured_at"), raw.keySet());
        assertFalse(raw.containsKey("agentId"));
        assertFalse(raw.containsKey("sourceKind"));
        assertFalse(raw.containsKey("sourceUpdatedAt"));
        assertFalse(raw.containsKey("capturedAt"));
    }

    @Test
    void captureKeepsValidEntriesWhenAnotherNodeFails() {
        AgentDefinition valid = agent("valid", "owner", AgentStatus.DRAFT, DefinitionType.AGENT);
        when(collection.get("valid")).thenReturn(Optional.of(valid));
        when(collection.get("missing")).thenReturn(Optional.empty());
        WorkflowGraph graph = WorkflowGraphParser.parse("""
            {"nodes":[
              {"id":"bad","type":"AGENT","config":{"agent_id":"missing"}},
              {"id":"good","type":"AGENT","config":{"agent_id":"valid"}}
             ],"edges":[]}
            """);

        Capture capture = capture(graph, "owner");

        assertEquals(List.of("node bad references an unknown agent/LLM definition: missing"), capture.errors);
        assertNotNull(capture.snapshots.get("good"));
        assertNotNull(capture.sources.get("good"));
        assertNull(capture.snapshots.get("bad"));
        assertNull(capture.sources.get("bad"));
    }

    private Capture capture(WorkflowGraph graph, String ownerUserId) {
        var errors = new ArrayList<String>();
        var snapshots = new LinkedHashMap<String, String>();
        var sources = new LinkedHashMap<String, String>();
        service.capture(graph, ownerUserId, errors, snapshots, sources);
        return new Capture(errors, snapshots, sources);
    }

    private void assertUnavailableOwnedEditableSkill(String skillId) {
        AgentDefinition agent = agent("private", "owner", AgentStatus.DRAFT, DefinitionType.AGENT);
        agent.skillIds = List.of(skillId);
        when(collection.get(agent.id)).thenReturn(Optional.of(agent));
        when(skillService.resolveAccessibleSkills(List.of(skillId), "owner"))
            .thenThrow(new ForbiddenException("skill is unavailable"));

        Capture capture = capture(graph("n1", "AGENT", agent.id), "owner");

        assertEquals(List.of("node n1 references an unavailable skill dependency"), capture.errors);
        assertTrue(capture.snapshots.isEmpty());
        assertTrue(capture.sources.isEmpty());
    }

    private WorkflowGraph graph(String nodeId, String nodeType, String agentId) {
        return WorkflowGraphParser.parse("{\"nodes\":[{\"id\":\"" + nodeId + "\",\"type\":\"" + nodeType
            + "\",\"config\":{\"agent_id\":\"" + agentId + "\"}}],\"edges\":[]}");
    }

    private AgentDefinition agent(String id, String userId, AgentStatus status, DefinitionType type) {
        var agent = new AgentDefinition();
        agent.id = id;
        agent.userId = userId;
        agent.status = status;
        agent.type = type;
        agent.createdAt = ZonedDateTime.parse("2026-08-01T10:00:00Z");
        agent.updatedAt = agent.createdAt;
        return agent;
    }

    private AgentPublishedConfig config(String systemPrompt) {
        var config = new AgentPublishedConfig();
        config.systemPrompt = systemPrompt;
        return config;
    }

    private AgentPublishedConfig snapshot(Capture capture, String nodeId) {
        return JSON.fromJSON(AgentPublishedConfig.class, capture.snapshots.get(nodeId));
    }

    private void assertLlmCallToolRejected(Capture capture) {
        assertEquals(List.of("node n1 agent snapshot contains an unsupported LLM call tool dependency"),
            capture.errors);
        assertTrue(capture.snapshots.isEmpty());
        assertTrue(capture.sources.isEmpty());
    }

    private AgentSnapshotSource source(Capture capture, String nodeId) {
        return JSON.fromJSON(AgentSnapshotSource.class, capture.sources.get(nodeId));
    }

    private record Capture(List<String> errors, Map<String, String> snapshots, Map<String, String> sources) {
    }
}
