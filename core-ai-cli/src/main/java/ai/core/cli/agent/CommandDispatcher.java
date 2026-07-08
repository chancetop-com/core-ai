package ai.core.cli.agent;

import ai.core.agent.profile.AgentProfile;
import ai.core.agent.profile.AgentProfileRegistry;
import ai.core.cli.auth.AuthCommandHandler;
import ai.core.cli.command.McpCommandHandler;
import ai.core.cli.command.HandlerContext;
import ai.core.cli.command.SkillCommandHandler;
import ai.core.cli.command.plugins.PluginCommandHandler;
import ai.core.cli.remote.RemoteConfig;
import ai.core.cli.ui.AnsiTheme;
import ai.core.cli.ui.TerminalUI;

import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

public class CommandDispatcher {

    private static final String POISON_PILL = "\0__EXIT__";

    private final TerminalUI ui;
    private final AgentSessionRunner session;
    private final ModelPicker modelPicker;
    private final AtomicReference<String> switchSessionId;
    private final AtomicReference<RemoteConfig> remoteConfig;
    private final HandlerContext handlers;
    private final String defaultServerUrl;
    private final AgentProfileRegistry agentProfileRegistry;

    CommandDispatcher(TerminalUI ui, ModelPicker modelPicker,
                      AtomicReference<String> switchSessionId,
                      AtomicReference<RemoteConfig> remoteConfig,
                      HandlerContext handlers, AgentSessionRunner session,
                      String defaultServerUrl, AgentProfileRegistry agentProfileRegistry) {
        this.ui = ui;
        this.session = session;
        this.modelPicker = modelPicker;
        this.switchSessionId = switchSessionId;
        this.remoteConfig = remoteConfig;
        this.handlers = handlers;
        this.defaultServerUrl = defaultServerUrl;
        this.agentProfileRegistry = agentProfileRegistry;
    }

    public void dispatch(String trimmed, BlockingQueue<String> queue) {
        var lower = trimmed.toLowerCase(Locale.ROOT);
        if (dispatchSessionCommand(lower, queue)) return;
        if (dispatchConfigCommand(trimmed, lower, queue)) return;
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
        if ("/thinking".equals(lower)) {
            session.handleThinking(lower);
            return true;
        }
        if ("/stats".equals(lower)) {
            session.handleStats();
            return true;
        }
        if ("/tools".equals(lower)) {
            session.handleTools();
            return true;
        }
        if ("/copy".equals(lower)) {
            session.handleCopy();
            return true;
        }
        if ("/undo".equals(lower)) {
            session.handleUndo();
            return true;
        }
        if ("/compact".equals(lower)) {
            session.handleCompact();
            return true;
        }
        if ("/resume".equals(lower)) {
            String picked = session.showSessionPicker();
            if (picked != null) {
                switchSessionId.set(picked);
                queue.offer(POISON_PILL);
            }
            return true;
        }
        if ("/clear".equals(lower)) {
            ui.printStreamingChunk("\u001B[2J\u001B[H");
            switchSessionId.set("");
            queue.offer(POISON_PILL);
            return true;
        }
        return false;
    }

    private boolean dispatchConfigCommand(String trimmed, String lower, BlockingQueue<String> queue) {
        if (lower.startsWith("/model ")) {
            modelPicker.switchModel(modelPicker.getCurrentModelName(), trimmed.split("\\s+", 2)[1].trim(), null);
            return true;
        }
        if (lower.startsWith("/thinking ")) {
            session.handleThinking(trimmed);
            return true;
        }
        if (lower.startsWith("/export")) {
            session.handleExport(trimmed);
            return true;
        }
        if (lower.startsWith("/memory")) {
            boolean isConfigCmd = "/memory enable".equals(lower) || "/memory disable".equals(lower);
            if (handlers.memoryCommand() == null) {
                ui.printStreamingChunk(AnsiTheme.MUTED + "  Memory not available.\n" + AnsiTheme.RESET);
            } else if (!handlers.memoryEnabled() && !isConfigCmd) {
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
        if (lower.startsWith("/agents")) {
            if (agentProfileRegistry == null) {
                ui.printStreamingChunk(AnsiTheme.MUTED + "  Agent profiles not available.\n" + AnsiTheme.RESET);
            } else {
                ui.printStreamingChunk(AnsiTheme.MUTED + "  Use /agents to list agents, /agents create <name> to create one.\n" + AnsiTheme.RESET);
            }
            return true;
        }
        return false;
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
        var sb = new StringBuilder("Available agents:\n");
        for (AgentProfile profile : profiles) {
            sb.append(String.format("  %s%-24s %s%s %s[%s]%s\n",
                    AnsiTheme.CMD_NAME, profile.name(),
                    AnsiTheme.CMD_DESC, profile.description(),
                    AnsiTheme.MUTED, profile.source(), AnsiTheme.RESET));
            if (profile.path() != null) {
                sb.append(String.format("    %spath: %s%s\n", AnsiTheme.MUTED, profile.path(), AnsiTheme.RESET));
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
}
