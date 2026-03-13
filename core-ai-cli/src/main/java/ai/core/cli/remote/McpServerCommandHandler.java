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
public class McpServerCommandHandler {
    private final TerminalUI ui;
    private final RemoteApiClient api;

    public McpServerCommandHandler(TerminalUI ui, RemoteApiClient api) {
        this.ui = ui;
        this.api = api;
    }

    public void handle() {
        var actions = List.of("List MCP servers", "Add MCP server", "Back");
        ui.printStreamingChunk("\n" + AnsiTheme.PROMPT + "  MCP Servers:" + AnsiTheme.RESET + "\n");
        int idx = ui.pickIndex(actions);
        if (idx < 0 || idx == actions.size() - 1) return;

        switch (idx) {
            case 0 -> listAndManage();
            case 1 -> addServer();
            default -> { }
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchMcpServers() {
        try {
            var json = api.get("/api/tools");
            if (json == null) return List.of();
            Map<String, Object> response = JsonUtil.fromJson(Map.class, json);
            var tools = (List<Map<String, Object>>) response.get("tools");
            if (tools == null) return List.of();
            return tools.stream().filter(t -> "MCP".equals(t.get("type"))).toList();
        } catch (RemoteApiException e) {
            ui.showError(e.getMessage());
            return List.of();
        }
    }

    private void listAndManage() {
        var servers = fetchMcpServers();
        if (servers.isEmpty()) {
            ui.printStreamingChunk(AnsiTheme.MUTED + "  No MCP servers found.\n" + AnsiTheme.RESET);
            return;
        }

        ui.printStreamingChunk(String.format("%n  %sMCP Servers (%d)%s%n", AnsiTheme.PROMPT, servers.size(), AnsiTheme.RESET));
        var labels = new ArrayList<String>();
        for (var server : servers) {
            var name = (String) server.get("name");
            var enabled = Boolean.TRUE.equals(server.get("enabled"));
            var desc = (String) server.get("description");
            var sb = new StringBuilder(name != null ? name : (String) server.get("id"));
            sb.append(enabled ? " [enabled]" : " [disabled]");
            if (desc != null && !desc.isBlank()) {
                sb.append(" - ").append(truncate(desc, 40));
            }
            labels.add(sb.toString());
        }

        ui.printStreamingChunk("\n" + AnsiTheme.PROMPT + "  Select server to manage:" + AnsiTheme.RESET + "\n");
        int idx = ui.pickIndex(labels);
        if (idx < 0) return;

        manageServer(servers.get(idx));
    }

    @SuppressWarnings("unchecked")
    private void manageServer(Map<String, Object> server) {
        var id = (String) server.get("id");
        var name = (String) server.get("name");
        var enabled = Boolean.TRUE.equals(server.get("enabled"));

        ui.printStreamingChunk(String.format("%n  %s%s%s%n", AnsiTheme.PROMPT, name, AnsiTheme.RESET));
        printField("ID", id);
        printField("Description", server.get("description"));
        printField("Category", server.get("category"));
        printField("Status", enabled ? "enabled" : "disabled");
        var config = (Map<String, String>) server.get("config");
        if (config != null) {
            for (var entry : config.entrySet()) {
                printField("  " + entry.getKey(), entry.getValue());
            }
        }

        var actions = new ArrayList<String>();
        actions.add(enabled ? "Disable" : "Enable");
        actions.add("Edit");
        actions.add("Delete");
        actions.add("Back");

        ui.printStreamingChunk("\n" + AnsiTheme.PROMPT + "  Action:" + AnsiTheme.RESET + "\n");
        int actionIdx = ui.pickIndex(actions);
        if (actionIdx < 0 || actionIdx == actions.size() - 1) return;

        switch (actions.get(actionIdx)) {
            case "Enable" -> enableServer(id, name);
            case "Disable" -> disableServer(id, name);
            case "Edit" -> editServer(id, server);
            case "Delete" -> deleteServer(id, name);
            default -> { }
        }
    }

    private void addServer() {
        ui.printStreamingChunk("\n" + AnsiTheme.PROMPT + "  Add MCP Server" + AnsiTheme.RESET + "\n");

        var name = ui.readRawLine("  Name: ");
        if (name == null || name.isBlank()) return;

        var description = ui.readRawLine("  Description (optional): ");
        var category = ui.readRawLine("  Category (optional): ");

        var transportTypes = List.of("HTTP (streamable-http)", "SSE", "STDIO");
        ui.printStreamingChunk("\n" + AnsiTheme.PROMPT + "  Transport type:" + AnsiTheme.RESET + "\n");
        int transportIdx = ui.pickIndex(transportTypes);
        if (transportIdx < 0) return;

        var config = readTransportConfig(transportIdx);
        if (config == null) return;

        var body = new LinkedHashMap<String, Object>();
        body.put("name", name.trim());
        if (description != null && !description.isBlank()) body.put("description", description.trim());
        if (category != null && !category.isBlank()) body.put("category", category.trim());
        body.put("config", config);
        body.put("enabled", true);

        try {
            var json = api.post("/api/tools/mcp-servers", body);
            if (json == null) {
                ui.showError("failed to create MCP server");
                return;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> result = JsonUtil.fromJson(Map.class, json);
            ui.printStreamingChunk("\n  " + AnsiTheme.SUCCESS + "MCP server created" + AnsiTheme.RESET + "\n");
            printField("ID", result.get("id"));
            printField("Name", result.get("name"));
            printField("Enabled", result.get("enabled"));
            ui.printStreamingChunk("\n");
        } catch (RemoteApiException e) {
            ui.showError(e.getMessage());
        }
    }

    private Map<String, String> readTransportConfig(int transportIdx) {
        var config = new LinkedHashMap<String, String>();
        switch (transportIdx) {
            case 0, 1 -> {
                var url = ui.readRawLine("  URL: ");
                if (url == null || url.isBlank()) return null;
                config.put("url", url.trim());
                if (transportIdx == 1) config.put("transport", "sse");
                var endpoint = ui.readRawLine("  Endpoint [/mcp]: ");
                if (endpoint != null && !endpoint.isBlank()) config.put("endpoint", endpoint.trim());
                var token = ui.readRawLine("  Bearer token (optional): ");
                if (token != null && !token.isBlank()) config.put("bearerToken", token.trim());
            }
            case 2 -> {
                var command = ui.readRawLine("  Command: ");
                if (command == null || command.isBlank()) return null;
                config.put("command", command.trim());
                var args = ui.readRawLine("  Args (comma-separated, optional): ");
                if (args != null && !args.isBlank()) config.put("args", args.trim());
            }
            default -> {
                return null;
            }
        }
        return config;
    }

    @SuppressWarnings("unchecked")
    private void editServer(String id, Map<String, Object> server) {
        ui.printStreamingChunk("\n" + AnsiTheme.PROMPT + "  Edit MCP Server" + AnsiTheme.RESET
                + AnsiTheme.MUTED + " (Enter to keep current)" + AnsiTheme.RESET + "\n");

        var currentName = (String) server.get("name");
        var currentDesc = (String) server.get("description");
        var currentCategory = (String) server.get("category");

        var body = new LinkedHashMap<String, Object>();

        var name = ui.readRawLine("  Name [" + currentName + "]: ");
        if (name != null && !name.isBlank()) body.put("name", name.trim());

        var desc = ui.readRawLine("  Description [" + (currentDesc != null ? currentDesc : "") + "]: ");
        if (desc != null && !desc.isBlank()) body.put("description", desc.trim());

        var cat = ui.readRawLine("  Category [" + (currentCategory != null ? currentCategory : "") + "]: ");
        if (cat != null && !cat.isBlank()) body.put("category", cat.trim());

        var editConfig = ui.readRawLine("  Edit config? (y/N): ");
        if (editConfig != null && editConfig.trim().toLowerCase(java.util.Locale.ROOT).startsWith("y")) {
            var currentConfig = (Map<String, String>) server.get("config");
            var newConfig = new LinkedHashMap<String, String>();
            if (currentConfig != null) {
                for (var entry : currentConfig.entrySet()) {
                    var val = ui.readRawLine("  " + entry.getKey() + " [" + entry.getValue() + "]: ");
                    newConfig.put(entry.getKey(), (val != null && !val.isBlank()) ? val.trim() : entry.getValue());
                }
            }
            body.put("config", newConfig);
        }

        if (body.isEmpty()) {
            ui.printStreamingChunk(AnsiTheme.MUTED + "  No changes.\n" + AnsiTheme.RESET);
            return;
        }

        try {
            var json = api.put("/api/tools/mcp-servers/" + id, body);
            if (json == null) {
                ui.showError("failed to update MCP server");
                return;
            }
            ui.printStreamingChunk("\n  " + AnsiTheme.SUCCESS + "MCP server updated" + AnsiTheme.RESET + "\n\n");
        } catch (RemoteApiException e) {
            ui.showError(e.getMessage());
        }
    }

    private void enableServer(String id, String name) {
        try {
            api.put("/api/tools/mcp-servers/" + id + "/enable", null);
            ui.printStreamingChunk("\n  " + AnsiTheme.SUCCESS + "Enabled " + name + AnsiTheme.RESET + "\n\n");
        } catch (RemoteApiException e) {
            ui.showError(e.getMessage());
        }
    }

    private void disableServer(String id, String name) {
        try {
            api.put("/api/tools/mcp-servers/" + id + "/disable", null);
            ui.printStreamingChunk("\n  " + AnsiTheme.SUCCESS + "Disabled " + name + AnsiTheme.RESET + "\n\n");
        } catch (RemoteApiException e) {
            ui.showError(e.getMessage());
        }
    }

    private void deleteServer(String id, String name) {
        var confirm = ui.readRawLine("  Delete " + name + "? (y/N): ");
        if (confirm == null || !confirm.trim().toLowerCase(java.util.Locale.ROOT).startsWith("y")) return;

        try {
            api.delete("/api/tools/mcp-servers/" + id);
            ui.printStreamingChunk("\n  " + AnsiTheme.SUCCESS + "Deleted " + name + AnsiTheme.RESET + "\n\n");
        } catch (RemoteApiException e) {
            ui.showError(e.getMessage());
        }
    }

    private void printField(String label, Object value) {
        if (value == null) return;
        ui.printStreamingChunk(String.format("  %s%-15s%s %s%n", AnsiTheme.MUTED, label + ":", AnsiTheme.RESET, value));
    }

    private String truncate(String text, int max) {
        if (text == null) return "";
        var clean = text.replaceAll("[\\r\\n]+", " ").strip();
        return clean.length() <= max ? clean : clean.substring(0, max) + "...";
    }
}
