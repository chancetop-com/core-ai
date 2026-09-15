package ai.core.server.sandboxhub;

import ai.core.api.server.sandboxhub.SandboxHubToolDetail;
import ai.core.utils.JsonUtil;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The catalog snapshot travels between replicas as JSON, so every field must survive a round-trip: a
 * shape without {@code @Property} fields (a record, for one) serializes to {@code {}} and every caller
 * then sees nulls.
 *
 * @author xander
 */
class SandboxHubCatalogSnapshotTest {
    @Test
    void everyFieldSurvivesTheJsonRoundTrip() {
        var detail = new SandboxHubToolDetail();
        detail.name = "menu_hub_search";
        detail.kind = "mcp";
        detail.timeoutSeconds = 120;

        var json = JsonUtil.toJson(SandboxHubCatalogSnapshot.of("menu-agent", List.of(detail)));
        var restored = JsonUtil.fromJson(SandboxHubCatalogSnapshot.class, json);

        assertNotNull(restored);
        assertEquals("menu-agent", restored.agentName);
        assertEquals(1, restored.details.size());
        assertEquals("menu_hub_search", restored.details.getFirst().name);
        assertEquals("mcp", restored.details.getFirst().kind);
    }

    @Test
    void detailsOrEmptyToleratesAnUnsetList() {
        assertEquals(List.of(), new SandboxHubCatalogSnapshot().detailsOrEmpty());
    }
}
