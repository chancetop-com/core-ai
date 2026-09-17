package ai.core.server.memory.experiment;

import ai.core.server.memory.AgentMemory;
import ai.core.server.memory.AgentMemoryService;
import ai.core.server.memory.KnowledgeType;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentMemoryExperimentServiceTest {
    private static final String AGENT_ID = "agent-1";

    private final MongoCollection<AgentMemoryExperimentConfig> configCollection = configCollection();
    private final MongoCollection<AgentMemoryExperimentRun> runCollection = runCollection();
    private final AgentMemoryService memoryService = memoryService();
    private final AgentMemoryExperimentService service = service();

    @Test
    void layeredModeWritesKnowledgeOutAndIndexesTheOtherLayers() {
        stubConfig(null);
        var tail = "-not-in-the-index";
        stubLayer(MemoryLayer.KNOWLEDGE, knowledge("m-know-1", "The user prefers metric units", 0));
        stubLayer(MemoryLayer.METHODS, method("m-method-1", "x".repeat(200) + tail, 1));
        stubLayer(MemoryLayer.TRAJECTORIES, trajectory("m-traj-1", "Shipped the tenant migration", 2));

        var result = service.prepareInjection(AGENT_ID);
        var prompt = result.promptInject.inject();

        assertTrue(result.injected);
        assertTrue(prompt.contains("## Agent Knowledge"));
        assertTrue(prompt.contains("- The user prefers metric units"));
        assertTrue(prompt.contains("## Indexed Memory"));
        assertTrue(prompt.contains("m-method-1 [methods/WORKFLOW_PATTERN]"));
        assertTrue(prompt.contains("m-traj-1 [trajectories/TRAJECTORY]"));
        assertTrue(prompt.contains("..."), "long content is summarised to one line");
        assertFalse(prompt.contains(tail), "indexed entries carry a summary, never the full text");
        assertEquals(List.of("m-know-1", "m-traj-1", "m-method-1"), result.injectedMemoryIds);
        assertEquals(1, result.layerBreakdown.get(MemoryLayer.KNOWLEDGE.mongoValue()));
        assertEquals(1, result.layerBreakdown.get(MemoryLayer.METHODS.mongoValue()));
        assertEquals(1, result.layerBreakdown.get(MemoryLayer.TRAJECTORIES.mongoValue()));
    }

    @Test
    void indexListsTheNewestEntriesFirst() {
        stubConfig(null);
        stubLayer(MemoryLayer.METHODS, method("m-old", "deploy the tenant", 0), method("m-new", "deploy the tenant", 9));

        var result = service.prepareInjection(AGENT_ID);

        assertEquals(List.of("m-new", "m-old"), result.injectedMemoryIds);
    }

    @Test
    void layeredModeCapsKnowledgeAndTheIndex() {
        stubConfig(null);
        var knowledge = new ArrayList<AgentMemory>();
        var methods = new ArrayList<AgentMemory>();
        for (int i = 0; i < 30; i++) {
            knowledge.add(knowledge("k-" + i, "knowledge " + i, i));
            methods.add(method("m-" + i, "method " + i, i));
        }
        stubLayer(MemoryLayer.KNOWLEDGE, knowledge.toArray(new AgentMemory[0]));
        stubLayer(MemoryLayer.METHODS, methods.toArray(new AgentMemory[0]));

        var result = service.prepareInjection(AGENT_ID);

        assertEquals(MemoryPolicy.KNOWLEDGE_FULL_MAX, result.layerBreakdown.get(MemoryLayer.KNOWLEDGE.mongoValue()));
        assertEquals(MemoryPolicy.INDEX_MAX, result.layerBreakdown.get(MemoryLayer.METHODS.mongoValue()));
        assertTrue(result.injectedMemoryIds.contains("k-29"), "the newest knowledge survives the cap");
        assertFalse(result.injectedMemoryIds.contains("k-0"), "the oldest knowledge is dropped first");
        assertTrue(result.injectedMemoryIds.contains("m-29"));
        assertFalse(result.injectedMemoryIds.contains("m-0"));
        assertTrue(result.promptInject.inject().length() < 8000, "the injected section stays bounded");
    }

    @Test
    void knowledgeOnlyConfigNeitherIndexesNorQueriesOtherLayers() {
        stubConfig(config(InjectionMode.LAYERED, List.of(MemoryLayer.KNOWLEDGE), 5));
        stubLayer(MemoryLayer.KNOWLEDGE, knowledge("m-know-1", "The user prefers metric units", 0));

        var result = service.prepareInjection(AGENT_ID);

        assertTrue(result.injected);
        assertFalse(result.promptInject.inject().contains("## Indexed Memory"));
        verify(memoryService, never()).findByAgentIdAndLayer(AGENT_ID, MemoryLayer.METHODS.mongoValue());
    }

    @Test
    void layeredModeWithoutMemoriesIsSkipped() {
        stubConfig(null);

        var result = service.prepareInjection(AGENT_ID);

        assertFalse(result.injected);
    }

    @Test
    void fullModeWritesTheTopKMemoriesOut() {
        stubConfig(config(InjectionMode.FULL, List.of(MemoryLayer.METHODS), 2));
        var tail = "-full-text-tail";
        stubLayer(MemoryLayer.METHODS,
                method("m-1", "first method", 0),
                method("m-2", "second method", 1),
                method("m-3", "third method " + tail, 2));

        var result = service.prepareInjection(AGENT_ID);
        var prompt = result.promptInject.inject();

        assertTrue(result.injected);
        assertTrue(prompt.contains("## Agent Memory"));
        assertTrue(prompt.contains(tail), "full-text mode writes the selected memories out");
        assertFalse(prompt.contains("## Indexed Memory"));
        assertEquals(List.of("m-3", "m-2"), result.injectedMemoryIds);
    }

    @Test
    void startRunRecordsTheInjectionMode() {
        service.startRun(AGENT_ID, "session-1", "session:session-1",
                config(InjectionMode.FULL, List.of(MemoryLayer.METHODS), 5), MemoryInjectionResult.skipped());

        var captor = ArgumentCaptor.forClass(AgentMemoryExperimentRun.class);
        verify(runCollection).insert(captor.capture());
        assertEquals(InjectionMode.FULL, captor.getValue().injectionMode);
    }

    @Test
    void startRunFallsBackToTheDefaultMode() {
        service.startRun(AGENT_ID, "session-1", "session:session-1",
                config(null, List.of(MemoryLayer.METHODS), 5), MemoryInjectionResult.skipped());

        var captor = ArgumentCaptor.forClass(AgentMemoryExperimentRun.class);
        verify(runCollection).insert(captor.capture());
        assertEquals(MemoryPolicy.DEFAULT_INJECTION_MODE, captor.getValue().injectionMode);
    }

    private void stubConfig(AgentMemoryExperimentConfig config) {
        when(configCollection.find(any(Query.class))).thenReturn(config == null ? List.of() : List.of(config));
    }

    private void stubLayer(MemoryLayer layer, AgentMemory... memories) {
        when(memoryService.findByAgentIdAndLayer(AGENT_ID, layer.mongoValue())).thenReturn(List.of(memories));
    }

    private AgentMemoryExperimentConfig config(InjectionMode mode, List<MemoryLayer> layers, Integer topK) {
        var config = new AgentMemoryExperimentConfig();
        config.id = "config-1";
        config.agentId = AGENT_ID;
        config.enabled = Boolean.TRUE;
        config.injectionProbability = 1.0;
        config.enabledLayers = layers;
        config.topK = topK;
        config.rankingStrategy = RankingStrategy.RECENCY;
        config.injectionMode = mode;
        return config;
    }

    private AgentMemory knowledge(String id, String content, int minute) {
        return memory(id, MemoryLayer.KNOWLEDGE, KnowledgeType.USER_PREFERENCE.name(), content, minute);
    }

    private AgentMemory method(String id, String content, int minute) {
        return memory(id, MemoryLayer.METHODS, "WORKFLOW_PATTERN", content, minute);
    }

    private AgentMemory trajectory(String id, String content, int minute) {
        return memory(id, MemoryLayer.TRAJECTORIES, "TRAJECTORY", content, minute);
    }

    private AgentMemory memory(String id, MemoryLayer layer, String type, String content, int minute) {
        var memory = new AgentMemory();
        memory.id = id;
        memory.agentId = AGENT_ID;
        memory.layer = layer;
        memory.type = type;
        memory.content = content;
        memory.createdAt = ZonedDateTime.parse("2026-09-01T09:00:00Z").plusMinutes(minute);
        return memory;
    }

    private AgentMemoryExperimentService service() {
        var service = new AgentMemoryExperimentService();
        service.configCollection = configCollection;
        service.runCollection = runCollection;
        service.memoryService = memoryService;
        return service;
    }

    private AgentMemoryService memoryService() {
        var service = mock(AgentMemoryService.class);
        when(service.formatKnowledgePrompt(anyList())).thenCallRealMethod();
        return service;
    }

    @SuppressWarnings("unchecked")
    private MongoCollection<AgentMemoryExperimentConfig> configCollection() {
        return mock(MongoCollection.class);
    }

    @SuppressWarnings("unchecked")
    private MongoCollection<AgentMemoryExperimentRun> runCollection() {
        return mock(MongoCollection.class);
    }
}
