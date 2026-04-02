package ai.core.cli.remote;

import ai.core.api.server.session.LoadSkillsRequest;
import ai.core.api.server.session.LoadSubAgentsRequest;
import ai.core.api.server.session.LoadSubAgentsResponse;
import ai.core.api.server.session.LoadToolsRequest;
import ai.core.api.server.session.LoadToolsResponse;
import ai.core.cli.ui.AnsiTheme;
import ai.core.cli.ui.TerminalUI;
import ai.core.utils.JsonUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @author stephen
 */
public class RemoteSessionCommandHandler {
    private final TerminalUI ui;
    private final RemoteApiClient api;
    private final String sessionId;
    private final Set<String> loadedToolIds;
    private final Set<String> loadedSkillIds;
    private final Set<String> loadedSubAgentIds;

    public RemoteSessionCommandHandler(TerminalUI ui, RemoteApiClient api, String sessionId,
                                Set<String> loadedToolIds, Set<String> loadedSkillIds, Set<String> loadedSubAgentIds) {
        this.ui = ui;
        this.api = api;
        this.sessionId = sessionId;
        this.loadedToolIds = loadedToolIds;
        this.loadedSkillIds = loadedSkillIds;
        this.loadedSubAgentIds = loadedSubAgentIds;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchTools() {
        String json;
        try {
            json = api.get("/api/tools");
        } catch (RemoteApiException e) {
            ui.showError(e.getMessage());
            return null;
        }
        if (json == null) {
            ui.showError("failed to fetch tools");
            return null;
        }
        Map<String, Object> response = JsonUtil.fromJson(Map.class, json);
        var tools = (List<Map<String, Object>>) response.get("tools");
        if (tools == null || tools.isEmpty()) {
            ui.printStreamingChunk(AnsiTheme.MUTED + "  No tools found.\n" + AnsiTheme.RESET);
            return null;
        }
        return tools;
    }

    @SuppressWarnings("unchecked")
    public void handleSkills() {
        String json;
        try {
            json = api.get("/api/skills");
        } catch (RemoteApiException e) {
            ui.showError(e.getMessage());
            return;
        }
        if (json == null) {
            ui.showError("failed to fetch skills");
            return;
        }
        Map<String, Object> response = JsonUtil.fromJson(Map.class, json);
        var skills = (List<Map<String, Object>>) response.get("skills");
        if (skills == null || skills.isEmpty()) {
            ui.printStreamingChunk(AnsiTheme.MUTED + "  No skills on server.\n" + AnsiTheme.RESET);
            return;
        }

        ui.printStreamingChunk(String.format("%n  %sServer Skills (%d)%s%n", AnsiTheme.PROMPT, skills.size(), AnsiTheme.RESET));
        var labels = new ArrayList<String>();
        for (var skill : skills) {
            var id = (String) skill.get("id");
            var qualifiedName = (String) skill.get("qualified_name");
            var desc = (String) skill.get("description");
            var sb = new StringBuilder(qualifiedName != null ? qualifiedName : (String) skill.get("name"));
            if (loadedSkillIds.contains(id)) sb.append(AnsiTheme.SUCCESS).append(" (loaded)").append(AnsiTheme.RESET);
            if (desc != null && !desc.isBlank()) sb.append(AnsiTheme.MUTED).append(" - ").append(truncate(desc, 40)).append(AnsiTheme.RESET);
            labels.add(sb.toString());
        }

        int selected = ui.pickIndex(labels);
        if (selected < 0) return;

        var skill = skills.get(selected);
        var skillId = (String) skill.get("id");
        var skillName = (String) skill.get("qualified_name");
        if (skillId == null) return;

        loadSkillToSession(skillId, skillName);
    }

    private void loadSkillToSession(String skillId, String skillName) {
        try {
            var request = new LoadSkillsRequest();
            request.skillIds = List.of(skillId);
            var resultJson = api.post("/api/sessions/" + sessionId + "/skills", request);
            if (resultJson != null) {
                loadedSkillIds.add(skillId);
                ui.printStreamingChunk("\n  " + AnsiTheme.SUCCESS + "Loaded skill: " + skillName + AnsiTheme.RESET + "\n");
            } else {
                ui.printStreamingChunk("\n  " + AnsiTheme.WARNING + "Skill was not loaded." + AnsiTheme.RESET + "\n");
            }
        } catch (RemoteApiException e) {
            ui.showError(e.getMessage());
        }
    }

    public void loadSubAgentToSession(String agentId) {
        try {
            var request = new LoadSubAgentsRequest();
            request.agentIds = List.of(agentId);
            var resultJson = api.post("/api/sessions/" + sessionId + "/subagents", request);
            if (resultJson != null) {
                var result = JsonUtil.fromJson(LoadSubAgentsResponse.class, resultJson);
                if (result.loadedSubAgents != null && !result.loadedSubAgents.isEmpty()) {
                    loadedSubAgentIds.add(agentId);
                    ui.printStreamingChunk("\n  " + AnsiTheme.SUCCESS + "Loaded subagent: " + String.join(", ", result.loadedSubAgents) + AnsiTheme.RESET + "\n\n");
                } else {
                    ui.printStreamingChunk("\n  " + AnsiTheme.WARNING + "SubAgent was not loaded." + AnsiTheme.RESET + "\n\n");
                }
            } else {
                ui.showError("failed to load subagent");
            }
        } catch (RemoteApiException e) {
            ui.showError(e.getMessage());
        }
    }

    public void handleTools() {
        var tools = fetchTools();
        if (tools == null) return;

        ui.printStreamingChunk(String.format("%n  %sTools (%d)%s%n", AnsiTheme.PROMPT, tools.size(), AnsiTheme.RESET));
        var labels = buildToolLabels(tools);
        ui.printStreamingChunk("\n" + AnsiTheme.PROMPT + "  Select tool:" + AnsiTheme.RESET + "\n");
        int selected = ui.pickIndex(labels);
        if (selected < 0) return;

        var tool = tools.get(selected);
        var toolId = (String) tool.get("id");
        var toolName = (String) tool.get("name");

        // Special handling for service-api: show API apps for selection
        if ("builtin-service-api".equals(toolId) || "service-api".equals(toolName)) {
            handleServiceApiTool(toolId);
            return;
        }

        printToolDetail(tool);

        var actions = List.of("Load to session", "Back");
        ui.printStreamingChunk("\n" + AnsiTheme.PROMPT + "  Action:" + AnsiTheme.RESET + "\n");
        int actionIdx = ui.pickIndex(actions);
        if (actionIdx != 0) return;

        loadToolToSession(toolId);
    }

    @SuppressWarnings("unchecked")
    private void handleServiceApiTool(String toolId) {
        // Fetch API apps from server
        String json;
        try {
            json = api.get("/api/tools/service-api/apps");
        } catch (RemoteApiException e) {
            ui.showError("failed to fetch API apps: " + e.getMessage());
            return;
        }
        if (json == null) {
            ui.showError("failed to fetch API apps");
            return;
        }

        Map<String, Object> response = JsonUtil.fromJson(Map.class, json);
        var apps = (List<Map<String, Object>>) response.get("apps");
        if (apps == null || apps.isEmpty()) {
            ui.printStreamingChunk(AnsiTheme.MUTED + "  No API definitions configured.\n" + AnsiTheme.RESET);
            return;
        }

        // Show API apps list
        ui.printStreamingChunk(String.format("%n  %sService API Apps (%d)%s%n", AnsiTheme.PROMPT, apps.size(), AnsiTheme.RESET));
        var appLabels = new ArrayList<String>();
        for (var app : apps) {
            var name = (String) app.get("name");
            var baseUrl = (String) app.get("base_url");
            var version = (String) app.get("version");
            var desc = (String) app.get("description");
            var sb = new StringBuilder(name != null ? name : "unknown");
            if (version != null) sb.append(" [v").append(version).append(']');
            if (baseUrl != null) sb.append(AnsiTheme.MUTED).append(" (").append(truncate(baseUrl, 30)).append(')').append(AnsiTheme.RESET);
            if (desc != null && !desc.isBlank()) sb.append(" - ").append(truncate(desc, 40));
            appLabels.add(sb.toString());
        }

        ui.printStreamingChunk("\n" + AnsiTheme.PROMPT + "  Select API app to load (Enter for all):" + AnsiTheme.RESET + "\n");
        int selected = ui.pickIndex(appLabels);
        if (selected < 0) return;

        var selectedApp = apps.get(selected);
        var appName = (String) selectedApp.get("name");
        var appToolId = "api-app:" + appName;

        // Show confirmation with app info
        printApiAppDetail(selectedApp);

        var actions = List.of("Load to session", "Back");
        ui.printStreamingChunk("\n" + AnsiTheme.PROMPT + "  Action:" + AnsiTheme.RESET + "\n");
        int actionIdx = ui.pickIndex(actions);
        if (actionIdx != 0) return;

        loadToolToSession(appToolId);
    }

    private void printApiAppDetail(Map<String, Object> app) {
        ui.printStreamingChunk(String.format("%n  %s%s%s%n", AnsiTheme.PROMPT, app.get("name"), AnsiTheme.RESET));
        printField("Base URL", app.get("base_url"));
        printField("Version", app.get("version"));
        var desc = (String) app.get("description");
        if (desc != null && !desc.isBlank()) {
            printField("Description", desc);
        }
    }

    private List<String> buildToolLabels(List<Map<String, Object>> tools) {
        var labels = new ArrayList<String>();
        for (var tool : tools) {
            var id = (String) tool.get("id");
            var name = (String) tool.get("name");
            var type = (String) tool.get("type");
            var category = (String) tool.get("category");
            var desc = (String) tool.get("description");
            var sb = new StringBuilder(name != null ? name : id);
            if (loadedToolIds.contains(id)) sb.append(AnsiTheme.SUCCESS + " (loaded)" + AnsiTheme.RESET);
            if (type != null) sb.append(" [").append(type).append(']');
            if (category != null) sb.append(" (").append(category).append(')');
            if (desc != null && !desc.isBlank()) sb.append(" - ").append(truncate(desc, 40));
            labels.add(sb.toString());
        }
        return labels;
    }

    private void printToolDetail(Map<String, Object> tool) {
        ui.printStreamingChunk(String.format("%n  %s%s%s%n", AnsiTheme.PROMPT, tool.get("name"), AnsiTheme.RESET));
        printField("ID", tool.get("id"));
        printField("Type", tool.get("type"));
        printField("Category", tool.get("category"));
        var desc = (String) tool.get("description");
        if (desc != null && !desc.isBlank()) {
            printField("Description", desc);
        }
    }

    private void loadToolToSession(String toolId) {
        if (toolId == null) {
            ui.showError("tool has no id");
            return;
        }
        try {
            var request = new LoadToolsRequest();
            request.toolIds = List.of(toolId);
            var resultJson = api.post("/api/sessions/" + sessionId + "/tools", request);
            if (resultJson == null) {
                ui.showError("failed to load tool");
                return;
            }
            var result = JsonUtil.fromJson(LoadToolsResponse.class, resultJson);
            if (result.loadedTools != null && !result.loadedTools.isEmpty()) {
                loadedToolIds.add(toolId);
                ui.printStreamingChunk("\n  " + AnsiTheme.SUCCESS + "Loaded: " + String.join(", ", result.loadedTools) + AnsiTheme.RESET + "\n");
            } else {
                ui.printStreamingChunk("\n  " + AnsiTheme.WARNING + "Tool was not loaded." + AnsiTheme.RESET + "\n");
            }
        } catch (RemoteApiException e) {
            ui.showError(e.getMessage());
        }
    }

    private void printField(String label, Object value) {
        if (value == null) return;
        ui.printStreamingChunk(String.format("  %s%-15s%s %s%n", AnsiTheme.MUTED, label + ":", AnsiTheme.RESET, value));
    }

    private static String truncate(String text, int max) {
        if (text == null) return "";
        var clean = text.replaceAll("[\\r\\n]+", " ").strip();
        return clean.length() <= max ? clean : clean.substring(0, max) + "...";
    }

    public void handleBuildAgent() {
        new CreateAgentCommandHandler(ui, api, sessionId).handle();
    }

    public Object handleAgentSwitch() {
        return new AgentCommandHandler(ui, api).handle();
    }
}
