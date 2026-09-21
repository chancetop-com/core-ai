package ai.core.cli.hub.dataset;

import ai.core.api.server.dataset.SchemaFieldView;
import ai.core.api.server.hub.HubDatasetView;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class DatasetRendererTest {
    private final DatasetRenderer renderer = new DatasetRenderer();

    @Test
    void listRowsCarryNameTypePermissionAndSchemaSize() {
        var text = renderer.listText(List.of(view("id-1", "menu-state", "SESSION", "WRITE", 2)));

        assertTrue(text.contains("menu-state"), text);
        assertTrue(text.contains("SESSION"), text);
        assertTrue(text.contains("WRITE"), text);
        assertTrue(text.contains("2 fields"), text);
        assertTrue(text.contains("id-1"), text);
    }

    @Test
    void emptyBindingsSaySo() {
        assertTrue(renderer.listText(List.of()).contains("no datasets bound to this session"));
    }

    @Test
    void detailListsSchemaFields() {
        var text = renderer.detailText(view("id-1", "menu-state", "SESSION", "WRITE", 1));

        assertTrue(text.contains("dataset_id:"), text);
        assertTrue(text.contains("MENU.md"), text);
        assertTrue(text.contains("string"), text);
    }

    @Test
    void summaryNamesUpdatedFieldsAndRecord() {
        var payload = "{\"status\":\"updated\",\"record_id\":\"r-1\",\"updated_fields\":[\"a\",\"b\"]}";

        assertEquals("updated (fields: a, b; record: r-1)", renderer.summary(payload));
    }

    @Test
    void summaryOfAStatusOnlyPayloadIsTheStatus() {
        assertEquals("saved", renderer.summary("{\"status\":\"saved\",\"dataset_id\":\"id-1\"}"));
    }

    @Test
    void summaryIgnoresANonWritePayload() {
        assertNull(renderer.summary("{\"state\":{},\"dataset_id\":\"id-1\"}"));
    }

    @Test
    void prettyKeepsPayloadContent() {
        var pretty = renderer.pretty("{\"records\":[{\"id\":1}],\"total\":1}");

        assertTrue(pretty.contains("\"total\" : 1"), pretty);
    }

    private HubDatasetView view(String datasetId, String name, String type, String permission, int schemaFields) {
        var view = new HubDatasetView();
        view.datasetId = datasetId;
        view.name = name;
        view.type = type;
        view.permission = permission;
        view.description = "menu publish state";
        var fields = new java.util.ArrayList<SchemaFieldView>();
        if (schemaFields > 0) fields.add(field("MENU.md", "string"));
        if (schemaFields > 1) fields.add(field("menuItems", "object"));
        view.schema = fields;
        return view;
    }

    private SchemaFieldView field(String name, String type) {
        var field = new SchemaFieldView();
        field.name = name;
        field.type = type;
        return field;
    }
}
