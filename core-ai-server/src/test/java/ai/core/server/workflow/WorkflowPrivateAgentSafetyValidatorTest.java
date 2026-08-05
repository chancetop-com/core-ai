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
import java.util.AbstractMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void rejectsSemanticallyMalformedSourceMetadata() {
        assertMalformedSource(null);
        assertMalformedSource("  ");
        assertMalformedSource("{}");
        assertMalformedSource("null");
        assertMalformedSource(" \n null \t");
        assertMalformedSource("{\"source_kind\":null}");
        assertMalformedSource("{\"source_kind\":\"UNKNOWN_PRIVATE_KIND\"}");
    }

    @Test
    void fatalErrorWhileReadingSourceMetadataPropagates() {
        var fatal = new LinkageError("fatal source read");
        var version = new WorkflowPublishedVersion();
        version.agentSnapshotSources = sourceMapThrowing(fatal);

        assertSame(fatal, assertThrows(LinkageError.class, () -> validator.validate(version)));
    }

    @Test
    void requiresMetadataCoverageForEverySnapshotWhenMetadataMapIsPresent() {
        var emptyMetadata = new WorkflowPublishedVersion();
        emptyMetadata.agentSnapshots = Map.of("secret-node", JSON.toJSON(new AgentPublishedConfig()));
        emptyMetadata.agentSnapshotSources = Map.of();
        assertEquals(List.of("node secret-node is missing agent snapshot source metadata"),
            validator.validate(emptyMetadata));

        var publishedSource = new AgentSnapshotSource();
        publishedSource.sourceKind = "PUBLISHED";
        var partialMetadata = new WorkflowPublishedVersion();
        partialMetadata.agentSnapshots = Map.of(
            "covered", JSON.toJSON(new AgentPublishedConfig()),
            "uncovered", JSON.toJSON(new AgentPublishedConfig()));
        partialMetadata.agentSnapshotSources = Map.of("covered", JSON.toJSON(publishedSource));
        assertEquals(List.of("node uncovered is missing agent snapshot source metadata"),
            validator.validate(partialMetadata));
    }

    @Test
    void rejectsPublishedOrOwnedSourceWithoutMatchingSnapshot() {
        var publishedSource = new AgentSnapshotSource();
        publishedSource.sourceKind = "PUBLISHED";
        var published = new WorkflowPublishedVersion();
        published.agentSnapshots = Map.of();
        published.agentSnapshotSources = Map.of("published-node", JSON.toJSON(publishedSource));
        assertEquals(List.of("node published-node is missing an agent snapshot"), validator.validate(published));

        WorkflowPublishedVersion owned = ownedVersion("owned-node", new AgentPublishedConfig());
        owned.agentSnapshots = Map.of();
        assertEquals(List.of("node owned-node is missing an agent snapshot"), validator.validate(owned));
    }

    @Test
    void rejectsNullBlankAndMalformedOwnedSnapshotValuesWithoutEchoingThem() {
        assertMalformedSnapshot(snapshotVersionWithValue(null));
        assertMalformedSnapshot(snapshotVersionWithValue("  "));
        assertMalformedSnapshot(snapshotVersionWithValue("null"));
        assertMalformedSnapshot(snapshotVersionWithValue(" \n null \t"));
        assertMalformedSnapshot(snapshotVersionWithValue("{malformed-private-snapshot"));
    }

    @Test
    void fatalErrorFromOwnedSnapshotValidationPropagates() {
        var fatal = new LinkageError("fatal sub-agent lookup");
        when(agentCollection.get("fatal-sub")).thenThrow(fatal);
        var config = new AgentPublishedConfig();
        config.subAgentIds = List.of("fatal-sub");

        assertSame(fatal, assertThrows(LinkageError.class,
            () -> validator.validate(ownedVersion("n1", config))));
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

    private void assertMalformedSource(String sourceJson) {
        var version = new WorkflowPublishedVersion();
        version.agentSnapshots = Map.of("n1", JSON.toJSON(new AgentPublishedConfig()));
        var sources = new LinkedHashMap<String, String>();
        sources.put("n1", sourceJson);
        version.agentSnapshotSources = sources;
        List<String> errors = validator.validate(version);
        assertEquals(List.of("node n1 has malformed agent snapshot source metadata"), errors);
        assertFalse(errors.getFirst().contains("UNKNOWN_PRIVATE_KIND"));
    }

    private Map<String, String> sourceMapThrowing(LinkageError fatal) {
        return new AbstractMap<>() {
            @Override
            public Set<Entry<String, String>> entrySet() {
                return Set.of(new SimpleImmutableEntry<>("n1", "unused") {
                    @Override
                    public String getValue() {
                        throw fatal;
                    }
                });
            }
        };
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
