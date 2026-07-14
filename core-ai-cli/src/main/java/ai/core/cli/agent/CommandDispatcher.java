package ai.core.cli.agent;

import ai.core.agent.profile.AgentProfile;
import ai.core.agent.profile.AgentProfileRegistry;
import ai.core.cli.auth.AuthCommandHandler;
import ai.core.cli.command.McpCommandHandler;
import ai.core.cli.command.HandlerContext;
import ai.core.cli.command.SkillCommandHandler;
import ai.core.cli.command.plugins.PluginCommandHandler;
import ai.core.cli.ui.AnsiTheme;
import ai.core.cli.ui.TerminalUI;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

public class CommandDispatcher {

    private static final String POISON_PILL = "\0__EXIT__";

    private final TerminalUI ui;
    private final AgentSessionRunnerCommandHandler sessionHandler;
    private final ModelPicker modelPicker;
    private final AtomicReference<String> switchSessionId;
    private final HandlerContext handlers;
    private final String defaultServerUrl;
    private final AgentProfileRegistry agentProfileRegistry;
    private final Path workspace;
    private final CreateAgentCommandHandler createAgentHandler;

    CommandDispatcher(TerminalUI ui, ModelPicker modelPicker,
                      AtomicReference<String> switchSessionId,
                      HandlerContext handlers, AgentSessionRunnerCommandHandler sessionHandler,
                      Config config) {
        this.ui = ui;
        this.sessionHandler = sessionHandler;
        this.modelPicker = modelPicker;
        this.switchSessionId = switchSessionId;
        this.handlers = handlers;
        this.defaultServerUrl = config.defaultServerUrl;
        this.agentProfileRegistry = config.agentProfileRegistry;
        this.workspace = config.workspace;
        this.createAgentHandler = new CreateAgentCommandHandler(ui, sessionHandler.getAgent().getLLMProvider(),
                modelPicker.getCurrentModelName(), config.workspace, config.agentProfileRegistry);
    }

    public void dispatch(String trimmed, BlockingQueue<String> queue) {
        var lower = trimmed.toLowerCase(Locale.ROOT);
        if (dispatchSessionCommand(lower, queue)) return;
        if (dispatchConfigCommand(trimmed, lower)) return;
        if (dispatchAgentCommand(lower)) return;
        if (dispatchPluginCommand(trimmed, lower, queue)) return;
        handlers.commands().handle(trimmed);
    }

    private boolean dispatchSessionCommand(String lower, BlockingQueue<String> queue) {
        if (lower.startsWith("/model ")) {
            return false;
        }
        if ("/model".equals(lower) || "/models".equals(lower)) {
            modelPicker.showModelPicker();
            return true;
        }
        if (lower.startsWith("/thinking ")) {
            return false;
        }
        switch (lower) {
            case "/thinking" -> {
                sessionHandler.handleThinking(lower);
                return true;
            }
            case "/stats" -> {
                sessionHandler.handleStats();
                return true;
            }
            case "/tools" -> {
                sessionHandler.handleTools();
                return true;
            }
            case "/copy" -> {
                sessionHandler.handleCopy();
                return true;
            }
            case "/undo" -> {
                sessionHandler.handleUndo();
                return true;
            }
            case "/compact" -> {
                sessionHandler.handleCompact();
                return true;
            }
            case "/resume", "/clear" -> {
                return handleSessionSwitch(lower, queue);
            }
            default -> {

            }
        }
        return false;
    }

    private boolean handleSessionSwitch(String lower, BlockingQueue<String> queue) {
        if ("/resume".equals(lower)) {
            String picked = sessionHandler.showSessionPicker();
            if (picked != null) {
                switchSessionId.set(picked);
                queue.offer(POISON_PILL);
            }
            return true;
        }
        ui.printStreamingChunk("\u001B[2J\u001B[H");
        switchSessionId.set("");
        queue.offer(POISON_PILL);
        return true;
    }

    private boolean dispatchConfigCommand(String trimmed, String lower) {
        if (lower.startsWith("/model ")) {
            modelPicker.switchModel(modelPicker.getCurrentModelName(), trimmed.split("\\s+", 2)[1].trim(), null);
            return true;
        }
        if (lower.startsWith("/thinking ")) {
            sessionHandler.handleThinking(trimmed);
            return true;
        }
        if (lower.startsWith("/export")) {
            sessionHandler.handleExport(trimmed);
            return true;
        }
        if (lower.startsWith("/memory")) {
            boolean isConfigCmd = "/memory enable".equals(lower) || "/memory disable".equals(lower);
            if (handlers.memoryCommand() == null) {
                ui.printStreamingChunk(AnsiTheme.MUTED + "  Memory not available.\n" + AnsiTheme.RESET);
            } else if (!isConfigCmd && !handlers.memoryEnabled()) {
                ui.printStreamingChunk(AnsiTheme.MUTED + "  Memory is disabled. Set agent.memory.enabled=true in agent.properties to enable.\n" + AnsiTheme.RESET);
            } else {
                handlers.memoryCommand().handle(trimmed);
            }
            return true;
        }
        if (lower.startsWith("/login") || "/logout".equals(lower)
                || "/status".equals(lower)
                || lower.startsWith("/server")) {
            new AuthCommandHandler(ui, defaultServerUrl).handle(trimmed);
            return true;
        }
        return false;
    }

    private boolean dispatchAgentCommand(String lower) {
        if ("/agents".equals(lower)) {
            listAgents();
            return true;
        }
        if ("/agents create".equals(lower)) {
            createAgentHandler.handle();
            return true;
        }
        if (lower.startsWith("/agents create ")) {
            String name = lower.substring("/agents create ".length()).trim();
            createAgent(name);
            return true;
        }
        if (lower.startsWith("/agents delete ")) {
            String name = lower.substring("/agents delete ".length()).trim();
            deleteAgent(name);
            return true;
        }
        return false;
    }

    private void createAgent(String name) {
        if (agentProfileRegistry == null || workspace == null) {
            ui.printStreamingChunk(AnsiTheme.MUTED + "  Agent profiles not available.\n" + AnsiTheme.RESET);
            return;
        }
        if (!name.matches("[a-zA-Z0-9][a-zA-Z0-9-]*")) {
            ui.printStreamingChunk(AnsiTheme.ERROR + "  Invalid agent name. Use letters, numbers, and hyphens.\n" + AnsiTheme.RESET);
            return;
        }
        Path agentsDir = workspace.resolve(".core-ai").resolve("agents");
        Path file = agentsDir.resolve(name + ".md");
        if (Files.exists(file)) {
            ui.printStreamingChunk(AnsiTheme.WARNING + "  Agent '" + name + "' already exists.\n" + AnsiTheme.RESET);
            return;
        }
        try {
            Files.createDirectories(agentsDir);
            String template = "---%n"
                    + "name: %s%n"
                    + "description: \"TODO: describe when to use this agent\"%n"
                    + "# Optional fields (uncomment to enable):%n"
                    + "# model: sonnet%n"
                    + "# temperature: 0.8%n"
                    + "# maxTurnNumber: 200%n"
                    + "# reasoningEffort: low | medium | high | max%n"
                    + "# tools:%n"
                    + "#   - Read%n"
                    + "#   - Bash%n"
                    + "#   - Glob%n"
                    + "#   - Grep%n"
                    + "#   - Write%n"
                    + "#   - Edit%n"
                    + "#   - task%n"
                    + "#   - WebSearch%n"
                    + "#   - WebFetch%n"
                    + "---%n%n"
                    + "You are %s. Describe what you do and how you should work.%n".formatted(name, name);
            Files.writeString(file, template);
            agentProfileRegistry.invalidateCache();
            ui.printStreamingChunk(AnsiTheme.CMD_NAME + "  Created " + file + AnsiTheme.RESET + "\n");
            ui.printStreamingChunk(AnsiTheme.MUTED + "  Edit this file to customize the agent, then use @"
                    + name + " <prompt> to invoke it.\n" + AnsiTheme.RESET);
        } catch (IOException e) {
            ui.printStreamingChunk(AnsiTheme.ERROR + "  Failed to create agent: " + e.getMessage() + "\n" + AnsiTheme.RESET);
        }
    }

    private void deleteAgent(String name) {
        Path agentsDir = workspace.resolve(".core-ai").resolve("agents");
        Path file = agentsDir.resolve(name + ".md");
        if (!Files.exists(file)) {
            ui.printStreamingChunk(AnsiTheme.WARNING + "  Agent '" + name + "' not found.\n" + AnsiTheme.RESET);
            return;
        }
        try {
            Files.delete(file);
            agentProfileRegistry.invalidateCache();
            ui.printStreamingChunk(AnsiTheme.CMD_NAME + "  Deleted " + file + AnsiTheme.RESET + "\n");
        } catch (IOException e) {
            ui.printStreamingChunk(AnsiTheme.ERROR + "  Failed to delete agent: " + e.getMessage() + "\n" + AnsiTheme.RESET);
        }
    }

    private void listAgents() {
        if (agentProfileRegistry == null) {
            ui.printStreamingChunk(AnsiTheme.MUTED + "  Agent profiles not available.\n" + AnsiTheme.RESET);
            return;
        }
        var profiles = agentProfileRegistry.listAll();
        if (profiles.isEmpty()) {
            ui.printStreamingChunk(AnsiTheme.MUTED + "  No agents registered.\n" + AnsiTheme.RESET);
            return;
        }
        var sb = new StringBuilder("Available agents:%n");
        for (AgentProfile profile : profiles) {
            sb.append(String.format("  %s%-24s %s%s %s[%s]%s%n",
                    AnsiTheme.CMD_NAME, profile.name(),
                    AnsiTheme.CMD_DESC, profile.description(),
                    AnsiTheme.MUTED, profile.source(), AnsiTheme.RESET));
            if (profile.path() != null) {
                sb.append(String.format("    %spath: %s%s%n", AnsiTheme.MUTED, profile.path(), AnsiTheme.RESET));
            }
        }
        ui.printStreamingChunk(sb.toString());
    }

    private boolean dispatchPluginCommand(String trimmed, String lower, BlockingQueue<String> queue) {
        if ("/skill".equals(lower) || "/skills".equals(lower)) {
            new SkillCommandHandler(ui).handle();
            return true;
        }
        if (lower.startsWith("/skill ")) {
            String content = new SkillCommandHandler(ui).loadSkillContent(trimmed.substring(7).trim());
            if (content != null) queue.offer(content);
            return true;
        }
        if ("/mcp".equals(lower)) {
            new McpCommandHandler(ui).handle();
            return true;
        }
        if ("/plugins".equals(lower) || "/plugin".equals(lower)) {
            new PluginCommandHandler(ui).handle();
            return true;
        }
        if (lower.startsWith("/plugins ") || lower.startsWith("/plugin ")) {
            new PluginCommandHandler(ui).handle(trimmed.split("\\s+", 2)[1]);
            return true;
        }
        return false;
    }

    record Config(String defaultServerUrl, AgentProfileRegistry agentProfileRegistry, Path workspace) {
    }
}
