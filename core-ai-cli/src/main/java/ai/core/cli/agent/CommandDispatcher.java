package ai.core.cli.agent;

import ai.core.cli.command.McpCommandHandler;
import ai.core.cli.command.MemoryCommandHandler;
import ai.core.cli.command.ReplCommandHandler;
import ai.core.cli.command.SkillCommandHandler;
import ai.core.cli.command.plugins.PluginCommandHandler;
import ai.core.cli.remote.RemoteCommandHandler;
import ai.core.cli.remote.RemoteConfig;
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
    private final AtomicReference<RemoteConfig> remoteConfig;
    private final ReplCommandHandler commands;
    private final MemoryCommandHandler memoryCommand;
    private final boolean memoryEnabled;

    public CommandDispatcher(Config config) {
        this.ui = config.ui;
        this.session = config.session;
        this.switchSessionId = config.switchSessionId;
        this.remoteConfig = config.remoteConfig;
        this.commands = config.commands;
        this.memoryCommand = config.memoryCommand;
        this.memoryEnabled = config.memoryEnabled;
    }

    public void dispatch(String trimmed, BlockingQueue<String> queue) {
        var lower = trimmed.toLowerCase(Locale.ROOT);
        if (dispatchSessionCommand(lower, queue)) return;
        if (dispatchConfigCommand(trimmed, lower, queue)) return;
        if (dispatchPluginCommand(trimmed, lower, queue)) return;
        commands.handle(trimmed);
    }

    private boolean dispatchSessionCommand(String lower, BlockingQueue<String> queue) {
        if (lower.startsWith("/model ")) {
            return false;
        }
        if ("/model".equals(lower) || "/models".equals(lower)) {
            session.showModelPicker();
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
            session.switchModel(getCurrentModelName(), trimmed.split("\\s+", 2)[1].trim(), null);
            return true;
        }
        if (lower.startsWith("/export")) {
            session.handleExport(trimmed);
            return true;
        }
        if (lower.startsWith("/memory")) {
            if (!memoryEnabled || memoryCommand == null) {
                ui.printStreamingChunk(AnsiTheme.MUTED + "  Memory is disabled. Set agent.memory.enabled=true in agent.properties to enable.\n" + AnsiTheme.RESET);
            } else {
                memoryCommand.handle(trimmed);
            }
            return true;
        }
        if ("/remote".equals(lower)) {
            var config = new RemoteCommandHandler(ui).handle();
            if (config != null) {
                remoteConfig.set(config);
                queue.offer(POISON_PILL);
            }
            return true;
        }
        return false;
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

    private String getCurrentModelName() {
        return session.getCurrentModelName();
    }

    public record Config(TerminalUI ui, AgentSessionRunner session,
                         AtomicReference<String> switchSessionId,
                         AtomicReference<RemoteConfig> remoteConfig,
                         ReplCommandHandler commands, MemoryCommandHandler memoryCommand,
                         boolean memoryEnabled) {
    }
}
