package ai.core.cli.hub.dataset;

import ai.core.api.server.dataset.SchemaFieldView;
import ai.core.api.server.hub.HubDatasetView;
import ai.core.cli.hub.HubRenderer;
import ai.core.utils.JsonUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Human-readable rendering of the dataset payloads and of the session's bindings. Payload text is data: stdout gets
 * the payload (pretty in human mode, verbatim in json/raw mode), while the one-line outcome summary goes to stderr so
 * a piped stdout stays parseable.
 *
 * @author stephen
 */
public class DatasetRenderer {
    private final HubRenderer hubRenderer = new HubRenderer();

    public String listText(List<HubDatasetView> datasets) {
        if (datasets.isEmpty()) return "  (no datasets bound to this session)\n";
        int nameWidth = datasets.stream().mapToInt(view -> length(view.name)).max().orElse(1) + 2;
        var text = new StringBuilder(256);
        for (var view : datasets) {
            text.append("  ").append(pad(nz(view.name), Math.min(nameWidth, 32)))
                    .append(pad(nz(view.type), 9))
                    .append(pad(nz(view.permission), 9))
                    .append(pad(schemaText(view.schema), 12))
                    .append(nz(view.datasetId));
            if (view.description != null && !view.description.isBlank()) {
                text.append("  ").append(view.description);
            }
            text.append('\n');
        }
        return text.toString();
    }

    public String detailText(HubDatasetView view) {
        var text = new StringBuilder(256);
        text.append("  ").append(nz(view.name))
                .append("\n    dataset_id:  ").append(nz(view.datasetId))
                .append("\n    type:        ").append(nz(view.type))
                .append("\n    permission:  ").append(nz(view.permission));
        if (view.description != null && !view.description.isBlank()) {
            text.append("\n    description: ").append(view.description);
        }
        text.append('\n');
        if (view.schema == null || view.schema.isEmpty()) {
            text.append("    schema:      (none)\n");
        } else {
            text.append("    schema:\n");
            for (var field : view.schema) {
                text.append("      ").append(pad(nz(field.name), 24)).append(nz(field.type)).append('\n');
            }
        }
        return text.toString();
    }

    public String pretty(String payload) {
        return hubRenderer.prettyJson(payload);
    }

    /** One stderr line describing what a write did, derived from the payload the server returned. */
    public String summary(String payload) {
        Map<String, Object> body;
        try {
            body = JsonUtil.toMap(payload);
        } catch (RuntimeException e) {
            return null;
        }
        var status = text(body.get("status"));
        if (status == null) return null;
        var parts = new ArrayList<String>();
        var fields = names(body.get("updated_fields"));
        if (fields.isEmpty()) fields = names(body.get("inserted_fields"));
        if (!fields.isEmpty()) parts.add("fields: " + String.join(", ", fields));
        var recordId = text(body.get("record_id"));
        if (recordId != null) parts.add("record: " + recordId);
        return parts.isEmpty() ? status : status + " (" + String.join("; ", parts) + ")";
    }

    private String schemaText(List<SchemaFieldView> schema) {
        int count = schema == null ? 0 : schema.size();
        return count == 0 ? "no schema" : count + (count == 1 ? " field" : " fields");
    }

    private List<String> names(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        var names = new ArrayList<String>();
        for (var item : list) {
            var name = text(item);
            if (name != null) names.add(name);
        }
        return names;
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String pad(String value, int width) {
        if (value.length() >= width) return value + "  ";
        return value + " ".repeat(width - value.length());
    }

    private int length(String value) {
        return value == null ? 0 : value.length();
    }

    private String nz(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
