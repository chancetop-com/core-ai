package ai.core.server.workflow;

import ai.core.server.domain.AgentDatasetConfig;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentPublishedConfig;
import ai.core.server.domain.AgentSandboxConfig;
import ai.core.server.domain.AgentSnapshotSource;
import ai.core.server.domain.AgentStatus;
import ai.core.server.domain.ToolRef;
import ai.core.server.domain.ToolSourceType;
import ai.core.server.domain.WorkflowPublishedVersion;
import core.framework.json.JSON;
import core.framework.mongo.MongoCollection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkflowPrivateAgentSafetyValidatorTest {
    @SuppressWarnings("unchecked")
    private final MongoCollection<AgentDefinition> agentCollection = mock(MongoCollection.class);
    private final WorkflowPrivateAgentSafetyValidator validator = new WorkflowPrivateAgentSafetyValidator();

    @BeforeEach
    void setUp() {
        validator.agentDefinitionCollection = agentCollection;
    }

    @Test
    void rejectsEachOwnerBoundResourceCategoryOnceWithoutStoredValues() {
        var config = new AgentPublishedConfig();
        config.systemPrompt = "private prompt value";
        config.model = "model-1";
        config.systemPromptId = "private-system-prompt";
        config.skillIds = List.of("private-skill", "second-private-skill");
        var firstDataset = new AgentDatasetConfig();
        firstDataset.datasetId = "owner-dataset";
        var secondDataset = new AgentDatasetConfig();
        secondDataset.datasetId = "second-owner-dataset";
        config.datasetConfig = List.of(firstDataset, secondDataset);
        config.enableMemory = Boolean.TRUE;
        var nullType = new ToolRef();
        nullType.id = "unknown-secret-tool";
        config.tools = List.of(
            ToolRef.of("private-mcp", ToolSourceType.MCP),
            ToolRef.of("private-agent-tool", ToolSourceType.AGENT),
            nullType);
        config.sandboxConfig = new AgentSandboxConfig();
        config.sandboxConfig.environmentVariables = Map.of("TOKEN", "secret-value");
        config.sandboxConfig.gitRepoUrl = "https://private/repo.git";

        List<String> errors = validator.validate(ownedVersion("n1", config));

        assertEquals(7, errors.size());
        assertEquals(1, errors.stream().filter(error -> error.contains("system prompt reference")).count());
        assertEquals(1, errors.stream().filter(error -> error.contains("skills")).count());
        assertEquals(1, errors.stream().filter(error -> error.contains("dataset")).count());
        assertEquals(1, errors.stream().filter(error -> error.contains("memory")).count());
        assertEquals(1, errors.stream().filter(error -> error.contains("tool type")).count());
        assertEquals(1, errors.stream().filter(error -> error.contains("environment variables")).count());
        assertEquals(1, errors.stream().filter(error -> error.contains("git repository")).count());
        assertTrue(errors.stream().allMatch(error -> error.startsWith("node n1")));
        String response = String.join("\n", errors);
        assertFalse(response.contains("private-system-prompt"));
        assertFalse(response.contains("private-skill"));
        assertFalse(response.contains("owner-dataset"));
        assertFalse(response.contains("private-mcp"));
        assertFalse(response.contains("TOKEN"));
        assertFalse(response.contains("secret-value"));
        assertFalse(response.contains("private/repo.git"));
        assertFalse(response.contains("private prompt value"));
    }

    @Test
    void acceptsPortableFieldsAndOnlyBuiltinOrApiTools() {
        var config = new AgentPublishedConfig();
        config.systemPrompt = "summarize the input";
        config.model = "model-1";
        config.temperature = 0.2;
        config.inputTemplate = "{{input}}";
        config.variables = Map.of("locale", "en");
        config.responseSchema = "{\"type\":\"string\"}";
        config.tools = List.of(
            ToolRef.of("builtin-web", ToolSourceType.BUILTIN),
            ToolRef.of("api-service:search", ToolSourceType.API));

        assertTrue(validator.validate(ownedVersion("n1", config)).isEmpty());
    }

    @Test
    void skipsPublishedAndLegacySnapshots() {
        var config = new AgentPublishedConfig();
        config.skillIds = List.of("historical-skill");
        WorkflowPublishedVersion published = ownedVersion("n1", config);
        AgentSnapshotSource source = JSON.fromJSON(AgentSnapshotSource.class, published.agentSnapshotSources.get("n1"));
        source.sourceKind = "PUBLISHED";
        published.agentSnapshotSources = Map.of("n1", JSON.toJSON(source));
        assertTrue(validator.validate(published).isEmpty());

        var legacy = new WorkflowPublishedVersion();
        legacy.agentSnapshots = Map.of("n1", JSON.toJSON(config));
        assertTrue(validator.validate(legacy).isEmpty());
    }

    @Test
    void rejectsMalformedSourceMetadataWithoutEchoingIt() {
        var version = new WorkflowPublishedVersion();
        version.agentSnapshots = Map.of("n1", JSON.toJSON(new AgentPublishedConfig()));
        version.agentSnapshotSources = Map.of("n1", "{malformed-secret-source");

        List<String> errors = validator.validate(version);

        assertEquals(List.of("node n1 has malformed agent snapshot source metadata"), errors);
        assertFalse(errors.getFirst().contains("malformed-secret-source"));
    }

    @Test
    void rejectsNullBlankMalformedAndMissingOwnedSnapshotsWithoutEchoingThem() {
        assertMalformedSnapshot(snapshotVersionWithValue(null));
        assertMalformedSnapshot(snapshotVersionWithValue("  "));
        assertMalformedSnapshot(snapshotVersionWithValue("{malformed-private-snapshot"));

        WorkflowPublishedVersion missing = ownedVersion("n1", new AgentPublishedConfig());
        missing.agentSnapshots = Map.of();
        assertMalformedSnapshot(missing);
    }

    @Test
    void ownedSnapshotRequiresEverySubAgentToHaveUsablePublishedConfig() {
        var draft = new AgentDefinition();
        draft.id = "draft-sub";
        draft.status = AgentStatus.DRAFT;
        var brokenPublished = new AgentDefinition();
        brokenPublished.id = "broken-published-sub";
        brokenPublished.status = AgentStatus.PUBLISHED;
        when(agentCollection.get("missing-sub")).thenReturn(Optional.empty());
        when(agentCollection.get("draft-sub")).thenReturn(Optional.of(draft));
        when(agentCollection.get("broken-published-sub")).thenReturn(Optional.of(brokenPublished));
        var config = new AgentPublishedConfig();
        config.subAgentIds = List.of("missing-sub");

        assertSubAgentError(config);

        config.subAgentIds = List.of("draft-sub");
        assertSubAgentError(config);

        config.subAgentIds = List.of("broken-published-sub");
        assertSubAgentError(config);

        var published = new AgentDefinition();
        published.id = "published-sub";
        published.status = AgentStatus.PUBLISHED;
        published.publishedConfig = new AgentPublishedConfig();
        when(agentCollection.get("published-sub")).thenReturn(Optional.of(published));
        config.subAgentIds = List.of("published-sub");
        assertTrue(validator.validate(ownedVersion("n1", config)).isEmpty());
    }

    private void assertSubAgentError(AgentPublishedConfig config) {
        List<String> errors = validator.validate(ownedVersion("n1", config));
        assertEquals(List.of("node n1 private agent snapshot requires unavailable sub-agents"), errors);
        assertTrue(config.subAgentIds.stream().noneMatch(errors.getFirst()::contains));
    }

    private void assertMalformedSnapshot(WorkflowPublishedVersion version) {
        List<String> errors = validator.validate(version);
        assertEquals(List.of("node n1 has a malformed private agent snapshot"), errors);
        assertFalse(errors.getFirst().contains("malformed-private-snapshot"));
    }

    private WorkflowPublishedVersion snapshotVersionWithValue(String value) {
        WorkflowPublishedVersion version = ownedVersion("n1", new AgentPublishedConfig());
        var snapshots = new LinkedHashMap<String, String>();
        snapshots.put("n1", value);
        version.agentSnapshots = snapshots;
        return version;
    }

    private WorkflowPublishedVersion ownedVersion(String nodeId, AgentPublishedConfig config) {
        var source = new AgentSnapshotSource();
        source.agentId = "private-agent";
        source.sourceKind = "OWNED_EDITABLE";
        source.capturedAt = ZonedDateTime.now();
        var version = new WorkflowPublishedVersion();
        version.agentSnapshots = Map.of(nodeId, JSON.toJSON(config));
        version.agentSnapshotSources = Map.of(nodeId, JSON.toJSON(source));
        return version;
    }
}
