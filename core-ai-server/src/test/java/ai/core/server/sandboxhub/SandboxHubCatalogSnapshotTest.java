package ai.core.server.sandboxhub;

import ai.core.api.server.dataset.SchemaFieldView;
import ai.core.api.server.sandboxhub.SandboxHubDatasetView;
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

    @Test
    void datasetsSurviveTheJsonRoundTripToo() {
        var view = new SandboxHubDatasetView();
        view.datasetId = "ds1";
        view.name = "menu-state";
        view.type = "SESSION";
        view.permission = "WRITE";
        view.description = "menus";
        var field = new SchemaFieldView();
        field.name = "menuItems";
        field.type = "STRING";
        field.label = "菜单";
        view.schema = List.of(field);

        var restored = JsonUtil.fromJson(SandboxHubCatalogSnapshot.class,
                JsonUtil.toJson(SandboxHubCatalogSnapshot.of("menu-agent", List.of(), List.of(view))));

        assertNotNull(restored);
        var dataset = restored.datasetsOrEmpty().getFirst();
        assertEquals("ds1", dataset.datasetId);
        assertEquals("menu-state", dataset.name);
        assertEquals("SESSION", dataset.type);
        assertEquals("WRITE", dataset.permission);
        assertEquals("menus", dataset.description);
        assertEquals("menuItems", dataset.schema.getFirst().name);
        assertEquals("菜单", dataset.schema.getFirst().label);
    }

    @Test
    void datasetsOrEmptyToleratesAnUnsetList() {
        assertEquals(List.of(), new SandboxHubCatalogSnapshot().datasetsOrEmpty());
    }
}
