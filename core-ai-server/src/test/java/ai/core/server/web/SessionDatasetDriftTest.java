package ai.core.server.web;

import ai.core.server.domain.AgentDatasetConfig;
import ai.core.server.domain.DatasetPermission;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A session keeps the dataset bindings it captured at creation; telling the user "start a new chat" must not fire on
 * an unchanged configuration (including a changed report target, which does not alter the tool set).
 */
class SessionDatasetDriftTest {
    private static AgentDatasetConfig binding(String datasetId, DatasetPermission permission) {
        var config = new AgentDatasetConfig();
        config.datasetId = datasetId;
        config.permission = permission;
        return config;
    }

    @Test
    void sameBindingsIgnoreOrderButNotPermission() {
        var write = binding("d1", DatasetPermission.WRITE);
        var read = binding("d2", DatasetPermission.READ);

        assertTrue(SessionDatasetDrift.sameBindings(List.of(write, read), List.of(read, write)));
        assertFalse(SessionDatasetDrift.sameBindings(List.of(write), List.of(binding("d1", DatasetPermission.FULL))));
        assertTrue(SessionDatasetDrift.sameBindings(null, List.of()));
        assertFalse(SessionDatasetDrift.sameBindings(null, List.of(write)));
    }

    @Test
    void sameBindingsIgnoreTheReportTarget() {
        var snapshot = binding("d1", DatasetPermission.WRITE);
        snapshot.isOutput = false;
        var current = binding("d1", DatasetPermission.WRITE);
        current.isOutput = true;

        assertTrue(SessionDatasetDrift.sameBindings(List.of(snapshot), List.of(current)));
    }
}
