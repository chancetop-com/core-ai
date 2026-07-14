package ai.core.cli.acp;

import ai.core.cli.DebugLog;
import ai.core.cli.agent.AgentSessionRunnerHelper;
import ai.core.cli.memory.MdMemoryProvider;
import ai.core.cli.memory.MemoryTriggerService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import ai.core.llm.LLMProviderType;
import ai.core.llm.LLMProviders;
import ai.core.llm.domain.RoleType;
import ai.core.mcp.client.McpClientManagerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Handles slash commands in ACP mode by reading Agent/Session state directly,
 * returning results as strings (no TerminalUI dependency).
 */
class AcpSlashCommandHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(AcpSlashCommandHandler.class);

    private final Path workspace;

    AcpSlashCommandHandler(Path workspace) {
        this.workspace = workspace;
    }

    /**
     * Returns a response string if the input is a known slash command, or null
     * to fall through to the agent.
     */
    @SuppressFBWarnings("CC_CYCLOMATIC_COMPLEXITY")
    String handle(String input, AcpSession session, LLMProviders providers) {
        var trimmed = input.trim();
        if (!trimmed.startsWith("/")) return null;

        var parts = trimmed.split("\\s+", 2);
        var command = parts[0].toLowerCase(Locale.ROOT);
        var agent = session.agent();

        return switch (command) {
            case "/help" -> """
                    Available commands:
                      /help           Show this help
                      /models         List available models
                      /model <name>   Switch to model
                      /thinking [lvl] Show or set reasoning effort (low/medium/high/off)
                      /debug          Toggle debug mode
                      /init           Create .core-ai/instructions.md
                      /tools          List available tools
                      /stats          Show session statistics
                      /undo           Undo last turn
                      /compact        Compact conversation
                      /export [file]  Export conversation to file
                      /memory         Manage memory (search/enable/disable/clear)
                      /skills         List installed skills
                      /mcp            List MCP server status
                      /sessions       List saved sessions
                      /resume <id>    Resume a saved session
                    """;
            case "/models" -> handleModels(providers);
            case "/model" -> handleModel(parts, providers);
            case "/thinking" -> handleThinking(parts);
            case "/debug" -> handleDebug();
            case "/init" -> handleInit();
            case "/tools" -> handleTools(agent);
            case "/stats" -> handleStats(session, agent);
            case "/undo" -> handleUndo(agent, session);
            case "/compact" -> handleCompact(agent, session);
            case "/export" -> handleExport(parts, session, agent);
            case "/skills" -> handleSkills();
            case "/mcp" -> handleMcp();
            case "/memory" -> handleMemory(parts);
            case "/sessions" -> handleSessions(session);
            case "/resume" -> handleResume(parts, session, agent);
            default -> null;
        };
    }

    private String handleModels(LLMProviders providers) {
        var types = providers.getProviderTypes();
        if (types.isEmpty()) return "No models available.";
        var def = providers.getProviderDefaultModel();
        var sb = new StringBuilder("Available models:\n");
        types.forEach(t -> sb.append("  ").append(t.name())
                .append(t == def ? " [active]" : "").append('\n'));
        return sb.toString();
    }

    private String handleModel(String[] parts, LLMProviders providers) {
        if (parts.length < 2 || parts[1].isBlank()) {
            return "Usage: /model <name>\nAvailable: "
                    + providers.getProviderTypes().stream().map(LLMProviderType::name)
                    .collect(Collectors.joining(", "));
        }
        var type = LLMProviderType.fromName(parts[1]);
        if (type == null || providers.getProvider(type) == null) {
            return "Unknown model: " + parts[1] + ". Available: "
                    + providers.getProviderTypes().stream().map(LLMProviderType::name)
                    .collect(Collectors.joining(", "));
        }
        providers.setDefaultProvider(type);
        return "Switched to model: " + type.name();
    }

    private String handleDebug() {
        if (DebugLog.isEnabled()) {
            DebugLog.disable();
            System.clearProperty("core.ai.debug");
            return "Debug mode: OFF";
        }
        DebugLog.enable();
        System.setProperty("core.ai.debug", "true");
        return "Debug mode: ON";
    }

    private String handleThinking(String[] parts) {
        if (parts.length < 2 || parts[1].isBlank()) {
            var current = AgentSessionRunnerHelper.loadReasoningEffortFromExtraBody();
            String level = current != null ? current.name().toLowerCase(Locale.ROOT) : "off (provider default)";
            return "Reasoning effort: " + level + "\nUsage: /thinking [low|medium|high|off]";
        }
        var arg = parts[1].trim().toLowerCase(Locale.ROOT);
        var level = AgentSessionRunnerHelper.parseLevel(arg);
        if (level != null || "off".equals(arg) || "none".equals(arg) || "default".equals(arg)) {
            String error = AgentSessionRunnerHelper.persistReasoningEffortToExtraBody(level);
            if (error != null) return "Error: " + error;
            String label = level != null ? level.name().toLowerCase(Locale.ROOT) : "off (provider default)";
            return "Reasoning effort set to " + label;
        }
        return "Invalid level: " + arg + ". Use low, medium, high, or off.";
    }

    private String handleInit() {
        var dir = workspace.resolve(".core-ai");
        var file = dir.resolve("instructions.md");
        if (file.toFile().exists()) {
            return ".core-ai/instructions.md already exists.";
        }
        try {
            Files.createDirectories(dir);
            Files.writeString(file, """
                    # Project Instructions

                    ## Guidelines
                    - Code comments in English
                    - Prefer self-descriptive code over comments

                    ## Project Structure
                    <!-- Describe your project structure here -->

                    ## Conventions
                    <!-- Add project-specific conventions -->
                    """);
            MemoryTriggerService.getInstance().ensureDirectories();
            return "\u2713 Created .core-ai/instructions.md \u2014 edit it to customize agent behavior.";
        } catch (IOException e) {
            return "Failed to create: " + e.getMessage();
        }
    }

    private String handleTools(ai.core.agent.Agent agent) {
        var tools = agent.getToolCalls();
        if (tools == null || tools.isEmpty()) return "No tools available.";
        var sb = new StringBuilder("Available tools (").append(tools.size()).append("):\n");
        tools.forEach(t -> {
            sb.append("  ").append(t.getName());
            if (t.getDescription() != null && !t.getDescription().isBlank()) {
                sb.append(" - ").append(t.getDescription());
            }
            sb.append('\n');
        });
        return sb.toString();
    }

    private String handleStats(AcpSession session, ai.core.agent.Agent agent) {
        var usage = agent.getCurrentTokenUsage();
        long turns = agent.getMessages().stream().filter(m -> m.role == RoleType.USER).count();
        return "Session: " + session.sessionId()
                + "\nModel: " + (agent.getModel() != null ? agent.getModel() : "default")
                + "\nTurns: " + turns
                + "\nTokens: " + usage.getTotalTokens() + " (prompt: " + usage.getPromptTokens()
                + ", completion: " + usage.getCompletionTokens() + ")";
    }

    private String handleUndo(ai.core.agent.Agent agent, AcpSession session) {
        var msgs = agent.getMessages();
        int idx = msgs.size() - 1;
        while (idx >= 0 && msgs.get(idx).role != RoleType.USER) idx--;
        if (idx < 0) return "Nothing to undo.";
        int removed = msgs.size() - idx;
        msgs.subList(idx, msgs.size()).clear();
        if (agent.hasPersistenceProvider()) agent.save(session.sessionId());
        return "Undone " + removed + " message(s).";
    }

    private String handleCompact(ai.core.agent.Agent agent, AcpSession session) {
        var msgs = agent.getMessages();
        var compression = agent.getCompression();
        if (msgs.size() <= 4 || compression == null) return "Nothing to compact.";
        int before = msgs.size();
        var compressed = compression.forceCompress(msgs);
        if (compressed.equals(msgs)) return "Nothing to compact.";
        msgs.clear();
        msgs.addAll(compressed);
        if (agent.hasPersistenceProvider()) agent.save(session.sessionId());
        return "Compacted: " + before + " \u2192 " + msgs.size() + " messages.";
    }

    private String handleExport(String[] parts, AcpSession session, ai.core.agent.Agent agent) {
        String path = parts.length > 1 && !parts[1].isBlank()
                ? parts[1].trim() : "session-" + session.sessionId() + ".md";
        var sb = new StringBuilder(4096);
        sb.append("# Session: ").append(session.sessionId()).append("\n\n");
        for (var msg : agent.getMessages()) {
            String text = msg.getTextContent();
            if (text != null) sb.append("## ").append(msg.role.name()).append("\n\n").append(text).append("\n\n");
        }
        try {
            Files.writeString(workspace.resolve(path), sb.toString());
            return "Exported to " + path;
        } catch (IOException e) {
            return "Export failed: " + e.getMessage();
        }
    }

    @SuppressFBWarnings("SACM_STATIC_ARRAY_CREATED_IN_METHOD")
    private String handleSkills() {
        var sb = new StringBuilder("Installed skills:\n");
        boolean found = false;
        for (String d : new String[]{".core-ai/skills", System.getProperty("user.home") + "/.core-ai/skills"}) {
            var dir = Path.of(d);
            if (!Files.isDirectory(dir)) continue;
            try (var s = Files.newDirectoryStream(dir)) {
                for (var entry : s) {
                    if (Files.isDirectory(entry) || entry.toString().endsWith(".md")) {
                        Path fn = entry.getFileName();
                        String n = fn != null ? fn.toString() : entry.toString();
                        if (n.endsWith(".md")) n = n.substring(0, n.length() - 3);
                        sb.append("  ").append(n).append('\n');
                        found = true;
                    }
                }
            } catch (IOException e) {
                LOGGER.debug("Cannot read skills directory: {}", d, e);
            }
        }
        if (!found) sb.append("  (none)");
        return sb.toString();
    }

    private String handleMcp() {
        var mgr = McpClientManagerRegistry.getManager();
        if (mgr == null || mgr.getServerNames() == null || mgr.getServerNames().isEmpty()) {
            return "No MCP servers configured.\nAdd mcp.servers to agent.properties.";
        }
        var sbn = new StringBuilder(256).append("MCP Servers (").append(mgr.getServerNames().size()).append("):\n");
        for (var n : mgr.getServerNames()) {
            try {
                int toolCount = mgr.safeListToolNames(n).size();
                sbn.append("  ").append(n).append(" (\u2713 ").append(toolCount).append(" tools)\n");
            } catch (Exception e) {
                sbn.append("  ").append(n).append(" (\u2717 ").append(e.getMessage()).append(")\n");
            }
        }
        return sbn.toString();
    }

    private String handleMemory(String[] parts) {
        var args = parts.length > 1 && !parts[1].isBlank() ? parts[1].trim() : "";
        if ("enable".equals(args)) return updateMemoryEnabledConfig(true);
        if ("disable".equals(args)) return updateMemoryEnabledConfig(false);
        if (args.startsWith("search ")) return memorySearch(args.substring(7).trim());
        if ("clear".equals(args)) {
            MemoryTriggerService.getInstance().clearKnowledge();
            return "Knowledge cleared and directory structure recreated.";
        }
        return """
                Memory commands:
                  /memory                    Show this help
                  /memory enable             Enable memory (set agent.memory.enabled=true)
                  /memory disable            Disable memory (set agent.memory.enabled=false)
                  /memory search <keyword>   Search memories by keyword
                  /memory clear              Delete knowledge wiki pages, recreate structure
                """;
    }

    private String handleSessions(AcpSession session) {
        var list = session.persistence().listSessions();
        if (list.isEmpty()) return "No saved sessions found.";
        var sbn = new StringBuilder(256).append("Saved sessions (").append(list.size()).append("):\n");
        int limit = Math.min(list.size(), 15);
        for (int i = 0; i < limit; i++) {
            var s = list.get(i);
            sbn.append("  ").append(s.id()).append(s.id().equals(session.sessionId()) ? " [current]" : "").append('\n');
        }
        sbn.append("\nUse /resume <sessionId> to switch.");
        return sbn.toString();
    }

    private String handleResume(String[] parts, AcpSession session, ai.core.agent.Agent agent) {
        if (parts.length < 2 || parts[1].isBlank()) {
            return "Usage: /resume <sessionId>\nUse /sessions to list available sessions.";
        }
        var target = parts[1].trim();
        if (target.equals(session.sessionId())) return "Already in this session.";
        try {
            agent.load(target);
            return "Resumed session: " + target + " (" + agent.getMessages().size() + " messages loaded).";
        } catch (Exception e) {
            return "Failed to resume session: " + e.getMessage();
        }
    }

    private String memorySearch(String query) {
        if (query.isBlank()) return "Usage: /memory search <keyword>";
        var memory = new MdMemoryProvider(workspace);
        var results = memory.searchMemories(query);
        if (results.isEmpty()) return "No entries matched '" + query + "'.";
        var sb = new StringBuilder("Memory entries matching '").append(query).append("' (").append(results.size()).append("):\n");
        for (var entry : results) {
            sb.append("  ").append(entry.toSummary()).append('\n');
        }
        return sb.toString();
    }

    private String updateMemoryEnabledConfig(boolean enabled) {
        try {
            var configFile = workspace.resolve("agent.properties");
            if (!Files.exists(configFile)) {
                configFile = workspace.resolve(".core-ai/agent.properties");
            }
            if (!Files.exists(configFile)) {
                return "Config file not found (agent.properties).";
            }
            var replacement = "agent.memory.enabled=" + enabled;
            var lines = Files.readAllLines(configFile, StandardCharsets.UTF_8);
            boolean found = false;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).trim().startsWith("agent.memory.enabled=")) {
                    lines.set(i, lines.get(i).replaceFirst("agent\\.memory\\.enabled\\s*=\\s*\\S+", replacement));
                    found = true;
                    break;
                }
            }
            if (!found) {
                lines.add(replacement);
            }
            Files.write(configFile, lines, StandardCharsets.UTF_8);
            return "Memory " + (enabled ? "enabled" : "disabled") + ". Restart for the change to take effect.";
        } catch (IOException e) {
            return "Failed to update memory config: " + e.getMessage();
        }
    }

    /**
     * Returns the list of all registered slash commands and their descriptions,
     * for use by ACP client (e.g. Obsidian plugin) to build an autocomplete dropdown.
     */
    List<CommandInfo> listCommands() {
        return List.of(
                new CommandInfo("/help", "Show this help"),
                new CommandInfo("/models", "List available models"),
                new CommandInfo("/model <name>", "Switch to model"),
                new CommandInfo("/thinking [level]", "Show or set reasoning effort (low/medium/high/off)"),
                new CommandInfo("/debug", "Toggle debug mode"),
                new CommandInfo("/init", "Create .core-ai/instructions.md"),
                new CommandInfo("/tools", "List available tools"),
                new CommandInfo("/stats", "Show session statistics"),
                new CommandInfo("/undo", "Undo last turn"),
                new CommandInfo("/compact", "Compact conversation"),
                new CommandInfo("/export [file]", "Export conversation to file"),
                new CommandInfo("/memory", "Manage memory (search/enable/disable/clear)"),
                new CommandInfo("/memory search <keyword>", "Search memories by keyword"),
                new CommandInfo("/memory enable", "Enable memory"),
                new CommandInfo("/memory disable", "Disable memory"),
                new CommandInfo("/memory clear", "Delete knowledge wiki pages"),
                new CommandInfo("/skills", "List installed skills"),
                new CommandInfo("/mcp", "List MCP server status"),
                new CommandInfo("/sessions", "List saved sessions"),
                new CommandInfo("/resume <id>", "Resume a saved session")
        );
    }

    public record CommandInfo(String command, String description) { }
}
