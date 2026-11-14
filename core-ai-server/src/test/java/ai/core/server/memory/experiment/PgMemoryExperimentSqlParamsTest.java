package ai.core.server.memory.experiment;


import ai.core.server.testsupport.RecordingDatabase;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @author stephen
 */
class PgMemoryExperimentSqlParamsTest {
    private static AgentMemoryExperimentConfig config() {
        var config = new AgentMemoryExperimentConfig();
        config.id = "c1";
        config.agentId = "a1";
        config.enabled = Boolean.TRUE;
        config.injectionProbability = 0.5;
        config.enabledLayers = List.of(MemoryLayer.KNOWLEDGE, MemoryLayer.METHODS);
        config.topK = 5;
        config.rankingStrategy = RankingStrategy.SEMANTIC;
        config.injectionMode = InjectionMode.LAYERED;
        return config;
    }

    private static AgentMemoryExperimentRun run() {
        var run = new AgentMemoryExperimentRun();
        run.id = "r1";
        run.agentId = "a1";
        run.sessionId = "s1";
        run.runId = "run1";
        run.enabledLayers = List.of(MemoryLayer.KNOWLEDGE);
        run.injectedMemoryIds = List.of("m1", "m2");
        run.layerBreakdown = Map.of("knowledge", 2);
        run.attributions = List.of(new MemoryAttribution());
        return run;
    }

    @Test
    void statements() {
        var database = new RecordingDatabase();
        var store = new PgAgentMemoryExperimentStore();
        store.database = database;

        store.saveConfig(config());
        store.getConfig("a1");
        store.findConfigs(0, 10);
        store.countConfigs();
        store.deleteConfig("a1");

        store.insertRun(run());
        store.updateRun(run());
        store.latestRunBySession("s1");
        store.runByRunId("run1");
        store.runsByAgent("a1", 10);
        store.listRuns("a1", 0, 10);
        store.listRuns(null, 0, 10);
        store.countRuns("a1");
        store.countRuns(null);
        store.getRun("r1");

        assertEquals(15, database.statementCount());
    }
}
