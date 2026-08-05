package ai.core.server.agent;

import ai.core.api.server.session.SessionConfig;
import ai.core.llm.LLMProvider;
import ai.core.llm.LLMProviderConfig;
import ai.core.llm.LLMProviderType;
import ai.core.llm.LLMProviders;
import ai.core.llm.domain.CaptionImageRequest;
import ai.core.llm.domain.CaptionImageResponse;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.EmbeddingRequest;
import ai.core.llm.domain.EmbeddingResponse;
import ai.core.llm.domain.RerankingRequest;
import ai.core.llm.domain.RerankingResponse;
import ai.core.llm.streaming.StreamingCallback;
import ai.core.persistence.PersistenceProviders;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentPublishedConfig;
import ai.core.server.domain.AgentStatus;
import ai.core.server.domain.DefinitionType;
import ai.core.server.domain.ToolRef;
import ai.core.server.settings.SystemSettingsService;
import ai.core.server.tool.ToolRegistryService;
import ai.core.tool.registry.ToolRegistryFactory;
import core.framework.mongo.MongoCollection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * @author Xander
 */
class SubAgentAssemblerTest {
    private SubAgentAssembler assembler;
    private SystemSettingsService systemSettingsService;

    @BeforeEach
    void setUp() {
        assembler = new SubAgentAssembler();
        var providers = new LLMProviders();
        providers.addProvider(LLMProviderType.LITELLM, new StubLLMProvider());
        providers.setDefaultProvider(LLMProviderType.LITELLM);
        assembler.llmProviders = providers;
        assembler.persistenceProviders = new PersistenceProviders();
        systemSettingsService = mock(SystemSettingsService.class);
        assembler.systemSettingsService = systemSettingsService;
    }

    @Test
    void pinnedModelWithoutMultiModalModelFallsBackToSystemSetting() {
        when(systemSettingsService.llmMultiModalModel()).thenReturn("azure/responses/gpt-5-mini");
        var config = new SessionConfig();
        config.model = "deepseek/deepseek-v4-flash";

        var agent = assembler.buildAgent(buildConfig(config));

        assertEquals("azure/responses/gpt-5-mini", agent.getMultiModalModel());
    }

    @Test
    void explicitMultiModalModelWins() {
        when(systemSettingsService.llmMultiModalModel()).thenReturn("system-mm-model");
        var config = new SessionConfig();
        config.model = "deepseek/deepseek-v4-flash";
        config.multiModalModel = "explicit-mm-model";

        var agent = assembler.buildAgent(buildConfig(config));

        assertEquals("explicit-mm-model", agent.getMultiModalModel());
    }

    @Test
    void preferCaptionPathForcesCaptionRouting() {
        when(systemSettingsService.llmMultiModalModel()).thenReturn("system-mm-model");
        var config = new SessionConfig();
        config.model = "azure/gpt-5-mini";
        config.preferCaptionPath = Boolean.TRUE;

        var agent = assembler.buildAgent(buildConfig(config));

        org.junit.jupiter.api.Assertions.assertFalse(agent.getExecutionContext().isVisionNative());
    }

    @Test
    void nullConfigFallsBackToSystemSetting() {
        when(systemSettingsService.llmMultiModalModel()).thenReturn("system-mm-model");

        var agent = assembler.buildAgent(buildConfig(null));

        assertEquals("system-mm-model", agent.getMultiModalModel());
    }

    @Test
    @SuppressWarnings("unchecked")
    void runtimeAssemblerRejectsDraftAgentDependency() {
        MongoCollection<AgentDefinition> collection = mock(MongoCollection.class);
        var draft = definition("draft-sub", DefinitionType.AGENT, AgentStatus.DRAFT);
        when(collection.get(draft.id)).thenReturn(Optional.of(draft));
        assembler.agentDefinitionCollection = collection;
        assembler.toolRegistryService = mock(ToolRegistryService.class);

        var tools = assembler.assemble(List.of(draft.id), "run-1", "caller-1");

        assertTrue(tools.isEmpty());
        verifyNoInteractions(assembler.toolRegistryService);
    }

    @Test
    @SuppressWarnings("unchecked")
    void runtimeAssemblerRejectsPublishedLlmCallInSubAgentSlot() {
        MongoCollection<AgentDefinition> collection = mock(MongoCollection.class);
        var llm = definition("llm-sub", DefinitionType.LLM_CALL, AgentStatus.PUBLISHED);
        llm.publishedConfig = new AgentPublishedConfig();
        when(collection.get(llm.id)).thenReturn(Optional.of(llm));
        assembler.agentDefinitionCollection = collection;
        assembler.toolRegistryService = mock(ToolRegistryService.class);

        var tools = assembler.assemble(List.of(llm.id), "run-1", "caller-1");

        assertTrue(tools.isEmpty());
        verifyNoInteractions(assembler.toolRegistryService);
    }

    @Test
    void subAgentToolResolutionReceivesAuthenticatedCaller() {
        var definition = definition("published-sub", DefinitionType.AGENT, AgentStatus.PUBLISHED);
        definition.publishedConfig = new AgentPublishedConfig();
        definition.publishedConfig.tools = List.of(ToolRef.fromLegacyToolId("llm-call:target"));
        var expected = ToolRegistryFactory.createEmpty();
        var registryService = mock(ToolRegistryService.class);
        when(registryService.resolveToToolRegistry(
            definition.publishedConfig.tools, "run-1", "caller-1")).thenReturn(expected);
        assembler.toolRegistryService = registryService;

        var actual = assembler.resolveToolsToRegistry(definition, "run-1", "caller-1");

        assertSame(expected, actual);
        verify(registryService).resolveToToolRegistry(
            definition.publishedConfig.tools, "run-1", "caller-1");
    }

    @Test
    void topLevelDraftAgentWithoutToolsResolvesAnEmptyRegistry() {
        var definition = definition("draft-top-level", DefinitionType.AGENT, AgentStatus.DRAFT);
        definition.publishedConfig = new AgentPublishedConfig();
        assembler.toolRegistryService = mock(ToolRegistryService.class);

        var actual = assembler.resolveTopLevelToolsToRegistry(definition, "session-1", "owner-1");

        assertNotNull(actual);
        verifyNoInteractions(assembler.toolRegistryService);
    }

    @Test
    void topLevelDraftAgentResolvesConfiguredToolsWithoutSubAgentEligibilityCheck() {
        var definition = definition("draft-top-level", DefinitionType.AGENT, AgentStatus.DRAFT);
        definition.publishedConfig = new AgentPublishedConfig();
        definition.publishedConfig.tools = List.of(ToolRef.fromLegacyToolId("builtin-service"));
        var expected = ToolRegistryFactory.createEmpty();
        var registryService = mock(ToolRegistryService.class);
        when(registryService.resolveToToolRegistry(
            definition.publishedConfig.tools, "session-1", "owner-1")).thenReturn(expected);
        assembler.toolRegistryService = registryService;

        var actual = assembler.resolveTopLevelToolsToRegistry(definition, "session-1", "owner-1");

        assertSame(expected, actual);
    }

    private AgentDefinition definition(String id, DefinitionType type, AgentStatus status) {
        var definition = new AgentDefinition();
        definition.id = id;
        definition.type = type;
        definition.status = status;
        return definition;
    }

    private SubAgentAssembler.BuildAgentConfig buildConfig(SessionConfig config) {
        return new SubAgentAssembler.BuildAgentConfig(config, ToolRegistryFactory.createEmpty(), null,
                "test-agent", null, null, null, null, null);
    }

    static class StubLLMProvider extends LLMProvider {
        StubLLMProvider() {
            super(new LLMProviderConfig("stub-model", 0.7, null));
        }

        @Override
        protected CompletionResponse doCompletion(CompletionRequest request) {
            throw new UnsupportedOperationException("not used in tests");
        }

        @Override
        protected CompletionResponse doCompletionStream(CompletionRequest request, StreamingCallback callback) {
            throw new UnsupportedOperationException("not used in tests");
        }

        @Override
        public EmbeddingResponse embeddings(EmbeddingRequest request) {
            throw new UnsupportedOperationException("not used in tests");
        }

        @Override
        public RerankingResponse rerankings(RerankingRequest request) {
            throw new UnsupportedOperationException("not used in tests");
        }

        @Override
        public CaptionImageResponse captionImage(CaptionImageRequest request) {
            throw new UnsupportedOperationException("not used in tests");
        }

        @Override
        public int maxTokens() {
            return 4096;
        }

        @Override
        public String name() {
            return "stub-llm";
        }
    }
}
