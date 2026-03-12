package ai.core.cli.remote;

import ai.core.cli.ui.AnsiTheme;
import ai.core.cli.ui.TerminalUI;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @author stephen
 */
final class AgentEditHandler {
    private static final List<EditField> COMMON_FIELDS = List.of(
        new EditField("name", "Name"),
        new EditField("description", "Description"),
        new EditField("system_prompt", "System Prompt"),
        new EditField("model", "Model"),
        new EditField("temperature", "Temperature"),
        new EditField("input_template", "Input Template")
    );
    private static final List<EditField> AGENT_FIELDS = List.of(
        new EditField("max_turns", "Max Turns"),
        new EditField("timeout_seconds", "Timeout (seconds)")
    );

    private static String truncate(String text, int max) {
        var clean = text.replaceAll("[\\r\\n]+", " ").strip();
        return clean.length() <= max ? clean : clean.substring(0, max) + "...";
    }

    private final TerminalUI ui;
    private final RemoteApiClient api;

    AgentEditHandler(TerminalUI ui, RemoteApiClient api) {
        this.ui = ui;
        this.api = api;
    }

    void edit(String agentId, boolean isLLMCall) {
        var fields = new ArrayList<>(COMMON_FIELDS);
        if (!isLLMCall) fields.addAll(AGENT_FIELDS);

        while (true) {
            var labels = new ArrayList<String>();
            for (var f : fields) labels.add(f.label);
            labels.add("Publish");
            labels.add("Back");

            ui.printStreamingChunk("\n" + AnsiTheme.PROMPT + "  Edit field:" + AnsiTheme.RESET + "\n");
            int idx = ui.pickIndex(labels);
            if (idx < 0 || idx == labels.size() - 1) return;

            if (idx == labels.size() - 2) {
                publish(agentId);
                continue;
            }

            var field = fields.get(idx);
            editField(agentId, field);
        }
    }

    private void editField(String agentId, EditField field) {
        var current = fetchFieldValue(agentId, field.key);
        if (current != null && !current.isEmpty()) {
            ui.printStreamingChunk(AnsiTheme.MUTED + "  Current: " + truncate(current, 120) + AnsiTheme.RESET + "\n");
        }
        var value = ui.readRawLine("  New " + field.label + ": ");
        if (value == null || value.isBlank()) return;

        var body = new LinkedHashMap<String, Object>();
        body.put(field.key, convertValue(field.key, value.trim()));
        try {
            api.put("/api/agents/" + agentId, body);
            ui.printStreamingChunk("  " + AnsiTheme.SUCCESS + field.label + " updated" + AnsiTheme.RESET + "\n");
        } catch (RemoteApiException e) {
            ui.showError(e.getMessage());
        }
    }

    private void publish(String agentId) {
        try {
            api.postEmpty("/api/agents/" + agentId + "/publish");
            ui.printStreamingChunk("  " + AnsiTheme.SUCCESS + "Published" + AnsiTheme.RESET + "\n");
        } catch (RemoteApiException e) {
            ui.showError(e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private String fetchFieldValue(String agentId, String key) {
        try {
            var json = api.get("/api/agents/" + agentId);
            if (json == null) return null;
            Map<String, Object> agent = ai.core.utils.JsonUtil.fromJson(Map.class, json);
            var val = agent.get(key);
            return val != null ? val.toString() : null;
        } catch (RemoteApiException e) {
            return null;
        }
    }

    private Object convertValue(String key, String value) {
        return switch (key) {
            case "temperature" -> Double.parseDouble(value);
            case "max_turns", "timeout_seconds" -> Integer.parseInt(value);
            default -> value;
        };
    }

    record EditField(String key, String label) { }
}
