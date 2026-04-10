package ai.core.cli.remote;

import ai.core.cli.ui.AnsiTheme;
import ai.core.cli.ui.TerminalUI;
import ai.core.utils.JsonUtil;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * @author stephen
 */
public class CreateAgentCommandHandler {
    private final TerminalUI ui;
    private final RemoteApiClient api;
    private final String sessionId;

    public CreateAgentCommandHandler(TerminalUI ui, RemoteApiClient api, String sessionId) {
        this.ui = ui;
        this.api = api;
        this.sessionId = sessionId;
    }

    @SuppressWarnings("unchecked")
    public void handle() {
        ui.printStreamingChunk("\n" + AnsiTheme.MUTED + "  Analyzing conversation to generate agent draft..." + AnsiTheme.RESET + "\n");

        var json = api.postEmpty("/api/sessions/" + sessionId + "/generate-agent-draft");
        if (json == null) {
            ui.showError("failed to generate agent draft");
            return;
        }

        Map<String, Object> draft = JsonUtil.fromJson(Map.class, json);
        printDraft(draft);

        var decision = askDecision();
        switch (decision) {
            case "y" -> createAgent(draft);
            case "e" -> editAndCreate(draft);
            default -> ui.printStreamingChunk(AnsiTheme.MUTED + "  Cancelled.\n" + AnsiTheme.RESET);
        }
    }

    @SuppressWarnings("unchecked")
    private void printDraft(Map<String, Object> draft) {
        ui.printStreamingChunk("\n" + AnsiTheme.PROMPT + "  Agent Draft" + AnsiTheme.RESET + "\n");
        ui.printStreamingChunk(AnsiTheme.MUTED + "  ─────────────────────────────────────────" + AnsiTheme.RESET + "\n");
        printField("Name", draft.get("name"));
        printField("Description", draft.get("description"));
        printField("Model", draft.get("model"));
        printField("Temperature", draft.get("temperature"));
        printField("Max Turns", draft.get("max_turns"));

        var tools = (List<Map<String, Object>>) draft.get("tools");
        if (tools != null && !tools.isEmpty()) {
            var toolNames = tools.stream().map(t -> (String) t.get("id")).toList();
            printField("Tools", String.join(", ", toolNames));
        }

        var systemPrompt = (String) draft.get("system_prompt");
        if (systemPrompt != null) {
            ui.printStreamingChunk(String.format("  %s%-18s%s%n", AnsiTheme.MUTED, "System Prompt:", AnsiTheme.RESET));
            for (var line : systemPrompt.split("\n")) {
                ui.printStreamingChunk(AnsiTheme.MUTED + "    " + line + AnsiTheme.RESET + "\n");
            }
        }

        var inputTemplate = (String) draft.get("input_template");
        if (inputTemplate != null) {
            ui.printStreamingChunk(String.format("  %s%-18s%s%n", AnsiTheme.MUTED, "Input Template:", AnsiTheme.RESET));
            for (var line : inputTemplate.split("\n")) {
                ui.printStreamingChunk(AnsiTheme.MUTED + "    " + line + AnsiTheme.RESET + "\n");
            }
        }

        ui.printStreamingChunk(AnsiTheme.MUTED + "  ─────────────────────────────────────────" + AnsiTheme.RESET + "\n");
    }

    private String askDecision() {
        var prompt = "  " + AnsiTheme.WARNING + "?" + AnsiTheme.RESET + " Create this agent? ("
                + AnsiTheme.CMD_NAME + "Y" + AnsiTheme.RESET + "es/"
                + AnsiTheme.CMD_NAME + "e" + AnsiTheme.RESET + "dit/"
                + AnsiTheme.CMD_NAME + "n" + AnsiTheme.RESET + "o): ";
        var input = ui.readRawLine(prompt);
        if (input == null) return "n";
        return input.trim().toLowerCase(Locale.ROOT);
    }

    @SuppressWarnings("unchecked")
    private void editAndCreate(Map<String, Object> draft) {
        draft.put("name", editField("Name", (String) draft.get("name")));
        draft.put("description", editField("Description", (String) draft.get("description")));
        draft.put("system_prompt", editField("System Prompt", (String) draft.get("system_prompt")));
        draft.put("input_template", editField("Input Template", (String) draft.get("input_template")));

        ui.printStreamingChunk("\n");
        printDraft(draft);
        var confirm = ui.readRawLine("  " + AnsiTheme.WARNING + "?" + AnsiTheme.RESET + " Confirm? (Y/n): ");
        if (confirm != null && !confirm.isBlank() && !confirm.trim().toLowerCase(Locale.ROOT).startsWith("y")) {
            ui.printStreamingChunk(AnsiTheme.MUTED + "  Cancelled.\n" + AnsiTheme.RESET);
            return;
        }
        createAgent(draft);
    }

    private String editField(String label, String currentValue) {
        ui.printStreamingChunk(AnsiTheme.MUTED + "  Current " + label + ": " + AnsiTheme.RESET);
        if (currentValue != null) {
            var display = currentValue.length() > 80 ? currentValue.substring(0, 80) + "..." : currentValue;
            ui.printStreamingChunk(display + "\n");
        } else {
            ui.printStreamingChunk("(empty)\n");
        }
        var input = ui.readRawLine("  New " + label + " (Enter to keep): ");
        if (input == null || input.isBlank()) return currentValue;
        return input.trim();
    }

    @SuppressWarnings("unchecked")
    private void createAgent(Map<String, Object> draft) {
        var json = api.post("/api/agents", draft);
        if (json == null) {
            ui.showError("failed to create agent");
            return;
        }
        Map<String, Object> created = JsonUtil.fromJson(Map.class, json);
        var agentId = (String) created.get("id");

        api.postEmpty("/api/agents/" + agentId + "/publish");

        ui.printStreamingChunk("\n  " + AnsiTheme.SUCCESS + "Agent created and published!" + AnsiTheme.RESET + "\n");
        ui.printStreamingChunk(AnsiTheme.MUTED + "  ID: " + AnsiTheme.RESET + agentId + "\n");
        ui.printStreamingChunk(AnsiTheme.MUTED + "  Use " + AnsiTheme.CMD_NAME + "/agent " + agentId + AnsiTheme.MUTED
                + " to view details, trigger or schedule." + AnsiTheme.RESET + "\n\n");
    }

    private void printField(String label, Object value) {
        if (value == null) return;
        ui.printStreamingChunk(String.format("  %-18s %s%n", AnsiTheme.MUTED + label + ":" + AnsiTheme.RESET, value));
    }
}
