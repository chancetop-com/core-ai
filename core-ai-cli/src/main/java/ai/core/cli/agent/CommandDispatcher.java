package ai.core.cli.agent;

import ai.core.cli.command.McpCommandHandler;
import ai.core.cli.command.MemoryCommandHandler;
import ai.core.cli.command.ReplCommandHandler;
import ai.core.cli.command.SkillCommandHandler;
import ai.core.cli.command.plugins.PluginCommandHandler;
import ai.core.cli.remote.RemoteCommandHandler;
import ai.core.cli.ui.AnsiTheme;
import ai.core.cli.ui.TerminalUI;

import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Dispatches slash commands to appropriate handlers.
 */
public class CommandDispatcher {

    private static final String POISON_PILL = "\0__EXIT__";

    private final TerminalUI ui;
    private final AgentSessionRunner session;
    private final AtomicReference<String> switchSessionId;
    private final AtomicReference<ai.core.cli.remote.RemoteConfig> remoteConfig;
    private final ReplCommandHandler commands;
    private final MemoryCommandHandler memoryCommand;
    private final boolean memoryEnabled;

    public CommandDispatcher(TerminalUI ui, AgentSessionRunner session,
                            AtomicReference<String> switchSessionId,
                            AtomicReference<ai.core.cli.remote.RemoteConfig> remoteConfig,
                            ReplCommandHandler commands, MemoryCommandHandler memoryCommand,
                            boolean memoryEnabled) {
        this.ui = ui;
        this.session = session;
        this.switchSessionId = switchSessionId;
        this.remoteConfig = remoteConfig;
        this.commands = commands;
        this.memoryCommand = memoryCommand;
        this.memoryEnabled = memoryEnabled;
    }

    public void dispatch(String trimmed, BlockingQueue<String> queue) {
        var lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.startsWith("/model ")) {
            session.switchModel(getCurrentModelName(), trimmed.split("\\s+", 2)[1].trim(), null);
        } else if ("/model".equals(lower) || "/models".equals(lower)) {
            session.showModelPicker();
        } else if ("/stats".equals(lower)) {
            session.handleStats();
        } else if ("/tools".equals(lower)) {
            session.handleTools();
        } else if ("/copy".equals(lower)) {
            session.handleCopy();
        } else if ("/undo".equals(lower)) {
            session.handleUndo();
        } else if ("/compact".equals(lower)) {
            session.handleCompact();
        } else if (lower.startsWith("/export")) {
            session.handleExport(trimmed);
        } else if (lower.startsWith("/memory")) {
            if (!memoryEnabled || memoryCommand == null) {
                ui.printStreamingChunk(AnsiTheme.MUTED + "  Memory is disabled. Set agent.memory.enabled=true in agent.properties to enable.\n" + AnsiTheme.RESET);
            } else {
                memoryCommand.handle(trimmed);
            }
        } else if ("/skill".equals(lower) || "/skills".equals(lower)) {
            new SkillCommandHandler(ui).handle();
        } else if (lower.startsWith("/skill ")) {
            String content = new SkillCommandHandler(ui).loadSkillContent(trimmed.substring(7).trim());
            if (content != null) queue.offer(content);
        } else if ("/mcp".equals(lower)) {
            new McpCommandHandler(ui).handle();
        } else if ("/plugins".equals(lower) || "/plugin".equals(lower)) {
            new PluginCommandHandler(ui).handle();
        } else if (lower.startsWith("/plugins ") || lower.startsWith("/plugin ")) {
            new PluginCommandHandler(ui).handle(trimmed.split("\\s+", 2)[1]);
        } else if ("/resume".equals(lower)) {
            String picked = session.showSessionPicker();
            if (picked != null) {
                switchSessionId.set(picked);
                queue.offer(POISON_PILL);
            }
        } else if ("/remote".equals(lower)) {
            var config = new RemoteCommandHandler(ui).handle();
            if (config != null) {
                remoteConfig.set(config);
                queue.offer(POISON_PILL);
            }
        } else if ("/clear".equals(lower)) {
            ui.printStreamingChunk("\u001B[2J\u001B[H");
            switchSessionId.set("");
            queue.offer(POISON_PILL);
        } else {
            commands.handle(trimmed);
        }
    }

    private String getCurrentModelName() {
        return session.getCurrentModelName();
    }
}
