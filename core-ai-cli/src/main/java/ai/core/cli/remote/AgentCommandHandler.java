package ai.core.cli.remote;

import ai.core.cli.ui.AnsiTheme;
import ai.core.cli.ui.TerminalUI;
import ai.core.utils.JsonUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @author stephen
 */
public class AgentCommandHandler {
    private static final int SEARCH_THRESHOLD = 5;

    private static boolean matchesKeyword(Map<String, Object> agent, String keyword) {
        for (var field : List.of("name", "description", "created_by", "type", "model")) {
            var value = agent.get(field);
            if (value != null && value.toString().toLowerCase(java.util.Locale.ROOT).contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> buildAgentLabels(List<Map<String, Object>> agents) {
        var labels = new ArrayList<String>();
        for (var agent : agents) {
            var name = (String) agent.get("name");
            var status = (String) agent.get("status");
            var createdBy = (String) agent.get("created_by");
            var sb = new StringBuilder(name != null ? name : (String) agent.get("id"));
            if (status != null) sb.append(" [").append(status).append(']');
            if (Boolean.TRUE.equals(agent.get("system_default"))) sb.append(" (default)");
            if (createdBy != null) sb.append(" by ").append(createdBy);
            labels.add(sb.toString());
        }
        return labels;
    }

    private static String truncate(String text, int max) {
        if (text == null) return "";
        var clean = text.replaceAll("[\\r\\n]+", " ").strip();
        return clean.length() <= max ? clean : clean.substring(0, max) + "...";
    }

    private final TerminalUI ui;
    private final RemoteApiClient api;

    public AgentCommandHandler(TerminalUI ui, RemoteApiClient api) {
        this.ui = ui;
        this.api = api;
    }

    @SuppressWarnings("unchecked")
    public AgentAction handle() {
        List<Map<String, Object>> agents;
        try {
            var json = api.get("/api/agents");
            if (json == null) {
                ui.showError("failed to fetch agents");
                return null;
            }
            Map<String, Object> response = JsonUtil.fromJson(Map.class, json);
            agents = (List<Map<String, Object>>) response.get("agents");
        } catch (RemoteApiException e) {
            ui.showError(e.getMessage());
            return null;
        }
        if (agents == null || agents.isEmpty()) {
            ui.printStreamingChunk(AnsiTheme.MUTED + "  No agents found.\n" + AnsiTheme.RESET);
            return null;
        }

        var filtered = filterAgents(agents);
        if (filtered.isEmpty()) {
            ui.printStreamingChunk(AnsiTheme.MUTED + "  No matching agents.\n" + AnsiTheme.RESET);
            return null;
        }

        ui.printStreamingChunk(AnsiTheme.PROMPT + "  Select agent:" + AnsiTheme.RESET + "\n");
        var labels = buildAgentLabels(filtered);

        int selected = ui.pickIndex(labels);
        if (selected < 0) return null;

        var agent = filtered.get(selected);
        var agentId = (String) agent.get("id");
        var agentName = (String) agent.get("name");
        var type = (String) agent.get("type");
        var isDefault = Boolean.TRUE.equals(agent.get("system_default"));
        printAgentDetail(agent);
        return handleAgentAction(agentId, agentName, "LLM_CALL".equals(type), isDefault);
    }

    private List<Map<String, Object>> filterAgents(List<Map<String, Object>> agents) {
        if (agents.size() <= SEARCH_THRESHOLD) {
            ui.printStreamingChunk("\n");
            return agents;
        }
        var keyword = ui.readRawLine("\n  Search (Enter to show all): ");
        if (keyword == null || keyword.isBlank()) return agents;
        var lower = keyword.trim().toLowerCase(java.util.Locale.ROOT);
        var result = new ArrayList<Map<String, Object>>();
        for (var agent : agents) {
            if (matchesKeyword(agent, lower)) result.add(agent);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private void printAgentDetail(Map<String, Object> agent) {
        ui.printStreamingChunk(String.format("%n  %s%s%s%n", AnsiTheme.PROMPT, agent.get("name"), AnsiTheme.RESET));
        printField("ID", agent.get("id"));
        printField("Type", agent.get("type"));
        printField("Created By", agent.get("created_by"));
        printField("Model", agent.get("model"));
        printField("Temperature", agent.get("temperature"));
        printField("Max Turns", agent.get("max_turns"));
        printField("Status", agent.get("status"));
        var tools = (List<Map<String, Object>>) agent.get("tools");
        if (tools != null && !tools.isEmpty()) {
            var toolNames = tools.stream().map(t -> (String) t.get("id")).toList();
            printField("Tools", String.join(", ", toolNames));
        }
        var prompt = (String) agent.get("system_prompt");
        if (prompt != null && !prompt.isBlank()) {
            printField("System Prompt", truncate(prompt, 80));
        }
        var responseSchema = agent.get("response_schema");
        if (responseSchema != null) {
            printResponseSchema(responseSchema);
        }
    }

    @SuppressWarnings("unchecked")
    private void printResponseSchema(Object responseSchema) {
        ui.printStreamingChunk(String.format("  %s%-15s%s%n", AnsiTheme.MUTED, "Response Schema:", AnsiTheme.RESET));
        try {
            var schemaJson = JsonUtil.toJson(responseSchema);
            var formatted = formatJson(schemaJson);
            for (var line : formatted.split("\n")) {
                ui.printStreamingChunk("  " + line + "\n");
            }
        } catch (Exception e) {
            ui.printStreamingChunk("  " + AnsiTheme.WARNING + "(failed to format schema)" + AnsiTheme.RESET + "\n");
        }
    }

    private String formatJson(String json) {
        if (json == null || json.isBlank()) return "";
        var sb = new StringBuilder();
        int indent = 0;
        boolean inString = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"' && (i == 0 || json.charAt(i - 1) != '\\')) {
                inString = !inString;
                sb.append(c);
            } else if (!inString) {
                switch (c) {
                    case '{', '[' -> {
                        indent += 2;
                        sb.append(c).append('\n').append("  ".repeat(indent));
                    }
                    case '}', ']' -> {
                        indent -= 2;
                        sb.append('\n').append("  ".repeat(Math.max(0, indent))).append(c);
                    }
                    case ',' -> {
                        sb.append(c).append('\n').append("  ".repeat(Math.max(0, indent)));
                    }
                    case ':' -> sb.append(": ");
                    default -> {
                        if (!Character.isWhitespace(c)) sb.append(c);
                    }
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private AgentAction handleAgentAction(String agentId, String agentName, boolean isLLMCall, boolean isDefault) {
        List<String> actions;
        if (isLLMCall) {
            actions = List.of("Test", "Edit", "Back");
        } else if (isDefault) {
            actions = List.of("Chat", "Load as SubAgent", "Back");
        } else {
            actions = List.of("Chat", "Load as SubAgent", "Edit", "Trigger run", "Schedule", "Back");
        }
        ui.printStreamingChunk("\n" + AnsiTheme.PROMPT + "  Action:" + AnsiTheme.RESET + "\n");
        int actionIdx = ui.pickIndex(actions);
        if (actionIdx < 0 || actionIdx == actions.size() - 1) return null;

        if (isLLMCall) {
            switch (actionIdx) {
                case 0 -> testLLMCall(agentId);
                case 1 -> new AgentEditHandler(ui, api).edit(agentId, true);
                default -> { }
            }
            return null;
        }
        var name = agentName != null ? agentName : agentId;
        return switch (actionIdx) {
            case 0 -> new AgentSwitch(agentId, name);
            case 1 -> new LoadAsSubAgent(agentId, name);
            case 2 -> {
                new AgentEditHandler(ui, api).edit(agentId, false);
                yield null;
            }
            case 3 -> {
                triggerRun(agentId);
                yield null;
            }
            case 4 -> {
                manageSchedule(agentId);
                yield null;
            }
            default -> null;
        };
    }

    private void testLLMCall(String agentId) {
        var input = ui.readRawLine("  Test input: ");
        if (input == null || input.isBlank()) return;
        callLLM(agentId, input.trim());
    }

    @SuppressWarnings("unchecked")
    private void callLLM(String agentId, String input) {
        var body = new LinkedHashMap<String, Object>();
        body.put("input", input);
        try {
            var json = api.post("/api/llm/" + agentId + "/call", body);
            if (json == null) {
                ui.showError("failed to call LLM");
                return;
            }
            Map<String, Object> result = JsonUtil.fromJson(Map.class, json);
            ui.printStreamingChunk("\n  " + AnsiTheme.SUCCESS + "Response:" + AnsiTheme.RESET + "\n");
            var output = result.get("output");
            if (output != null) {
                ui.printStreamingChunk("  " + output.toString().replace("\n", "\n  ") + "\n");
            }
            var tokenUsage = (Map<String, Object>) result.get("token_usage");
            if (tokenUsage != null) {
                var sb = AnsiTheme.MUTED + "  tokens: input=" + tokenUsage.get("input") + " output=" + tokenUsage.get("output") + AnsiTheme.RESET + '\n';
                ui.printStreamingChunk(sb);
            }
            ui.printStreamingChunk("\n");
        } catch (RemoteApiException e) {
            ui.showError(e.getMessage());
        }
    }

    private void triggerRun(String agentId) {
        var input = ui.readRawLine("  Input (Enter to use default): ");
        var body = new LinkedHashMap<String, Object>();
        if (input != null && !input.isBlank()) {
            body.put("input", input.trim());
        }

        try {
            var json = api.post("/api/runs/agent/" + agentId + "/trigger", body);
            if (json == null) {
                ui.showError("failed to trigger run");
                return;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> result = JsonUtil.fromJson(Map.class, json);
            ui.printStreamingChunk("\n  " + AnsiTheme.SUCCESS + "Run triggered" + AnsiTheme.RESET + "\n");
            printField("Run ID", result.get("run_id"));
            printField("Status", result.get("status"));
            ui.printStreamingChunk("\n");
        } catch (RemoteApiException e) {
            ui.showError(e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void manageSchedule(String agentId) {
        List<Map<String, Object>> schedules = null;
        try {
            var json = api.get("/api/schedules/agent/" + agentId + "/list");
            if (json != null) {
                Map<String, Object> response = JsonUtil.fromJson(Map.class, json);
                schedules = (List<Map<String, Object>>) response.get("schedules");
            }
        } catch (RemoteApiException e) {
            ui.showError(e.getMessage());
            return;
        }

        if (schedules != null && !schedules.isEmpty()) {
            ui.printStreamingChunk("\n" + AnsiTheme.PROMPT + "  Existing schedules:" + AnsiTheme.RESET + "\n");
            for (var schedule : schedules) {
                var cron = (String) schedule.get("cron_expression");
                var enabled = Boolean.TRUE.equals(schedule.get("enabled"));
                var tz = schedule.get("timezone");
                var statusTag = enabled ? AnsiTheme.SUCCESS + "enabled" : AnsiTheme.MUTED + "disabled";
                var sb = new StringBuilder(100);
                sb.append("  ").append(AnsiTheme.CMD_NAME).append(cron).append(AnsiTheme.RESET)
                  .append(" [").append(statusTag).append(AnsiTheme.RESET).append(']');
                if (tz != null) {
                    sb.append(AnsiTheme.MUTED).append(" (").append(tz).append(')').append(AnsiTheme.RESET);
                }
                sb.append('\n');
                ui.printStreamingChunk(sb.toString());
            }
        }

        var actions = new ArrayList<>(List.of("Create new schedule"));
        if (schedules != null && !schedules.isEmpty()) {
            actions.add("Delete schedule");
        }
        actions.add("Back");

        ui.printStreamingChunk("\n");
        int idx = ui.pickIndex(actions);
        if (idx < 0 || idx == actions.size() - 1) return;

        if (idx == 0) {
            createSchedule(agentId);
        } else if (idx == 1) {
            deleteSchedule(schedules);
        }
    }

    private void createSchedule(String agentId) {
        var cron = ui.readRawLine("  Cron expression (e.g. 0 9 * * *): ");
        if (cron == null || cron.isBlank()) return;

        var tz = ui.readRawLine("  Timezone [UTC]: ");
        if (tz == null || tz.isBlank()) tz = "UTC";

        var input = ui.readRawLine("  Input (Enter to use agent default): ");

        var body = new LinkedHashMap<String, Object>();
        body.put("agent_id", agentId);
        body.put("cron_expression", cron.trim());
        body.put("timezone", tz.trim());
        if (input != null && !input.isBlank()) {
            body.put("input", input.trim());
        }

        try {
            var json = api.post("/api/schedules", body);
            if (json == null) {
                ui.showError("failed to create schedule");
                return;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> result = JsonUtil.fromJson(Map.class, json);
            ui.printStreamingChunk("\n  " + AnsiTheme.SUCCESS + "Schedule created" + AnsiTheme.RESET + "\n");
            printField("ID", result.get("id"));
            printField("Cron", result.get("cron_expression"));
            printField("Next Run", result.get("next_run_at"));
            ui.printStreamingChunk("\n");
        } catch (RemoteApiException e) {
            ui.showError(e.getMessage());
        }
    }

    private void deleteSchedule(List<Map<String, Object>> schedules) {
        var labels = new ArrayList<String>();
        for (var s : schedules) {
            var cron = (String) s.get("cron_expression");
            var enabled = Boolean.TRUE.equals(s.get("enabled"));
            labels.add(cron + (enabled ? " [enabled]" : " [disabled]"));
        }

        ui.printStreamingChunk("\n" + AnsiTheme.PROMPT + "  Select schedule to delete:" + AnsiTheme.RESET + "\n");
        int idx = ui.pickIndex(labels);
        if (idx < 0) return;

        var scheduleId = (String) schedules.get(idx).get("id");
        try {
            api.delete("/api/schedules/" + scheduleId);
            ui.printStreamingChunk("  " + AnsiTheme.SUCCESS + "Schedule deleted" + AnsiTheme.RESET + "\n\n");
        } catch (RemoteApiException e) {
            ui.showError(e.getMessage());
        }
    }

    private void printField(String label, Object value) {
        if (value == null) return;
        ui.printStreamingChunk(String.format("  %s%-15s%s %s%n", AnsiTheme.MUTED, label + ":", AnsiTheme.RESET, value));
    }

    sealed interface AgentAction permits AgentSwitch, LoadAsSubAgent { }
    record AgentSwitch(String agentId, String agentName) implements AgentAction { }
    record LoadAsSubAgent(String agentId, String agentName) implements AgentAction { }
}
