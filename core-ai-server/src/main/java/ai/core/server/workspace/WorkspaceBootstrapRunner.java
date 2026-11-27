package ai.core.server.workspace;

import ai.core.sandbox.SandboxConstants;
import ai.core.server.sandbox.SandboxClient;
import ai.core.tool.ToolCallResult;
import ai.core.utils.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Runs workspace initialization after a runtime pod becomes healthy and before the workspace is
 * marked READY: seeds AGENTS.md / NOTES.md without overwriting, executes bootstrap.sh once per
 * volume, executes start.sh on every wake via async bash + /tasks/{id} polling, rotates logs to the
 * latest 20 files, and probes /workspace/.core-ai/env presence.
 *
 * @author stephen
 */
public class WorkspaceBootstrapRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorkspaceBootstrapRunner.class);
    private static final DateTimeFormatter LOG_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);
    private static final int MAX_LOG_FILES = 20;
    private static final int MAX_LOG_SNIPPET_CHARS = 4096;
    private static final int SYNC_TIMEOUT_MS = 30_000;
    private static final String EXIT_PREFIX = "CORE_AI_EXIT_CODE=";
    private static final String LOG_FILE_PREFIX = "CORE_AI_LOG_FILE=";
    private static final String LOG_START_MARKER = "---CORE_AI_LOG_TAIL---";
    private static final String BOOTSTRAP_PATH = WorkspaceDefaults.MOUNT_PATH + "/.core-ai/bootstrap.sh";
    private static final String START_PATH = WorkspaceDefaults.MOUNT_PATH + "/.core-ai/start.sh";
    private static final String ENV_PATH = WorkspaceDefaults.MOUNT_PATH + "/.core-ai/env";

    public static String sha256Hex(String content) {
        if (content == null || content.isBlank()) return null;
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
            var sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format(Locale.ROOT, "%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static boolean scriptChanged(String script, String recordedHash) {
        if (recordedHash == null || recordedHash.isBlank()) return false;
        if (script == null || script.isBlank()) return false;
        return !recordedHash.equals(sha256Hex(script));
    }

    static String buildScriptWrapper(String kind, String scriptPath, String logFile) {
        var pattern = WorkspaceDefaults.MOUNT_PATH + "/.core-ai/logs/" + kind + "-*.log";
        return "mkdir -p /workspace/.core-ai/logs\n"
                + "bash " + scriptPath + " >> '" + logFile + "' 2>&1\n"
                + "RC=$?\n"
                + "ls -1t " + pattern + " 2>/dev/null | tail -n +" + (MAX_LOG_FILES + 1) + " | xargs -r rm -f\n"
                + "echo '" + EXIT_PREFIX + "'$RC\n"
                + "echo '" + LOG_FILE_PREFIX + logFile + "'\n"
                + "echo '" + LOG_START_MARKER + "'\n"
                + "tail -n 60 '" + logFile + "' 2>/dev/null | head -c " + MAX_LOG_SNIPPET_CHARS + "\n";
    }

    private static RuntimeClientFactory defaultFactory() {
        return host -> new SandboxClientAdapter(new SandboxClient(host, SandboxConstants.RUNTIME_PORT, 30));
    }

    private final RuntimeClientFactory clientFactory;
    private final int bootstrapTimeoutSeconds;
    private final int startTimeoutSeconds;
    private final long pollIntervalMs;

    public WorkspaceBootstrapRunner(WorkspaceConfig config) {
        this(defaultFactory(), config.bootstrapTimeoutSeconds, config.startTimeoutSeconds, 1000L);
    }

    WorkspaceBootstrapRunner(RuntimeClientFactory clientFactory, int bootstrapTimeoutSeconds,
                             int startTimeoutSeconds, long pollIntervalMs) {
        this.clientFactory = clientFactory;
        this.bootstrapTimeoutSeconds = Math.max(1, bootstrapTimeoutSeconds);
        this.startTimeoutSeconds = Math.max(1, startTimeoutSeconds);
        this.pollIntervalMs = Math.max(10L, pollIntervalMs);
    }

    public WorkspaceBootstrapState prepare(WorkspaceRecord workspace, String host, boolean forceRerunBootstrap) {
        try (var client = clientFactory.open(host)) {
            seedTemplates(client, workspace.id);
            var afterBootstrap = runBootstrapPhase(client, workspace, forceRerunBootstrap);
            return runStartPhase(client, workspace, afterBootstrap);
        }
    }

    private WorkspaceBootstrapState runBootstrapPhase(RuntimeClient client, WorkspaceRecord workspace,
                                                      boolean forceRerunBootstrap) {
        var bootstrapCompletedAt = workspace.bootstrapCompletedAt;
        var bootstrapHash = workspace.bootstrapScriptHash;
        var startHash = workspace.startScriptHash;
        var lastLog = workspace.lastBootstrapLog;
        if (bootstrapCompletedAt != null && !forceRerunBootstrap) {
            return new WorkspaceBootstrapState(bootstrapCompletedAt, bootstrapHash, startHash, lastLog, workspace.envFilePresent);
        }
        var scriptHash = resolveAndStageScript(client, BOOTSTRAP_PATH, workspace.bootstrapScript);
        if (scriptHash != null) {
            var outcome = runAsyncScript(client, workspace.id, "bootstrap", BOOTSTRAP_PATH, bootstrapTimeoutSeconds);
            lastLog = outcome.logSnippet();
            if (!outcome.succeeded()) {
                var state = new WorkspaceBootstrapState(bootstrapCompletedAt, bootstrapHash, startHash, lastLog, probeEnvPresent(client));
                throw new BootstrapFailure(WorkspaceException.BOOTSTRAP_FAILED, outcome.errorMessage(), state);
            }
            bootstrapHash = scriptHash;
        }
        return new WorkspaceBootstrapState(ZonedDateTime.now(), bootstrapHash, startHash, lastLog, workspace.envFilePresent);
    }

    private WorkspaceBootstrapState runStartPhase(RuntimeClient client, WorkspaceRecord workspace,
                                                  WorkspaceBootstrapState prior) {
        var startHash = prior.startScriptHash();
        var lastLog = prior.lastBootstrapLog();
        var resolvedStartHash = resolveAndStageScript(client, START_PATH, workspace.startScript);
        if (resolvedStartHash != null) {
            var outcome = runAsyncScript(client, workspace.id, "start", START_PATH, startTimeoutSeconds);
            if (outcome.logSnippet() != null && !outcome.logSnippet().isBlank()) {
                lastLog = outcome.logSnippet();
            }
            if (!outcome.succeeded()) {
                var state = new WorkspaceBootstrapState(prior.bootstrapCompletedAt(), prior.bootstrapScriptHash(),
                        startHash, lastLog, probeEnvPresent(client));
                throw new BootstrapFailure(WorkspaceException.START_FAILED, outcome.errorMessage(), state);
            }
            startHash = resolvedStartHash;
        }
        return new WorkspaceBootstrapState(prior.bootstrapCompletedAt(), prior.bootstrapScriptHash(),
                startHash, lastLog, probeEnvPresent(client));
    }

    private void seedTemplates(RuntimeClient client, String workspaceId) {
        var result = runSyncBash(client, WorkspaceTemplates.buildEnsureCommand());
        if (!result.isCompleted()) {
            LOGGER.warn("failed to seed workspace templates, workspace={}, result={}", workspaceId, result.getResult());
        }
    }

    private String resolveAndStageScript(RuntimeClient client, String scriptPath, String configuredScript) {
        if (configuredScript != null && !configuredScript.isBlank()) {
            var encoded = Base64.getEncoder().encodeToString(configuredScript.getBytes(StandardCharsets.UTF_8));
            var cmd = "mkdir -p /workspace/.core-ai /workspace/.core-ai/logs\n"
                    + "printf '%s' '" + encoded + "' | base64 -d > " + scriptPath + "\n"
                    + "chmod 700 " + scriptPath + "\n";
            var staged = runSyncBash(client, cmd);
            if (!staged.isCompleted()) {
                throw new RuntimeException("failed to stage script " + scriptPath + ": " + staged.getResult());
            }
            return sha256Hex(configuredScript);
        }
        var probe = runSyncBash(client, "if [ -f " + scriptPath + " ]; then sha256sum " + scriptPath + " | awk '{print $1}'; fi");
        if (!probe.isCompleted() || probe.getResult() == null) return null;
        var hash = probe.getResult().trim();
        return hash.length() == 64 ? hash : null;
    }

    private boolean probeEnvPresent(RuntimeClient client) {
        var probe = runSyncBash(client, "if [ -f " + ENV_PATH + " ]; then echo PRESENT; else echo ABSENT; fi");
        return probe.isCompleted() && probe.getResult() != null && probe.getResult().contains("PRESENT");
    }

    private ScriptOutcome runAsyncScript(RuntimeClient client, String workspaceId, String kind,
                                         String scriptPath, int timeoutSeconds) {
        var logFile = WorkspaceDefaults.MOUNT_PATH + "/.core-ai/logs/" + kind + "-"
                + LOG_TS.format(ZonedDateTime.now()) + ".log";
        var command = buildScriptWrapper(kind, scriptPath, logFile);
        var args = new LinkedHashMap<String, Object>();
        args.put("command", command);
        args.put("run_in_background", Boolean.TRUE);
        args.put("timeout", timeoutSeconds * 1000);

        var submitted = client.execute("run_bash_command", JsonUtil.toJson(args));
        var runtimeTaskId = extractRuntimeTaskId(submitted);
        if (runtimeTaskId == null) {
            return new ScriptOutcome(false, kind + ".sh failed to launch: " + submitted.getResult(), submitted.getResult());
        }

        var deadlineNanos = System.nanoTime() + (timeoutSeconds + 15L) * 1_000_000_000L;
        while (System.nanoTime() < deadlineNanos) {
            sleepPoll();
            var polled = client.pollTask(runtimeTaskId);
            if (!polled.isPending()) {
                return interpretTerminal(kind, logFile, polled);
            }
        }

        client.cancelTask(runtimeTaskId);
        LOGGER.warn("workspace {} script timed out, workspace={}, task={}", kind, workspaceId, runtimeTaskId);
        return new ScriptOutcome(false, kind + ".sh timed out after " + timeoutSeconds + "s",
                "[" + logFile + "]\ntimed out after " + timeoutSeconds + "s");
    }

    private ScriptOutcome interpretTerminal(String kind, String defaultLogFile, ToolCallResult polled) {
        var text = polled.getResult() == null ? "" : polled.getResult();
        if (!polled.isCompleted()) {
            return new ScriptOutcome(false, kind + ".sh failed: " + text, truncate("[" + defaultLogFile + "]\n" + text));
        }
        var exitCode = parseExitCode(text);
        var logFile = parseLineValue(text, LOG_FILE_PREFIX);
        if (logFile == null || logFile.isBlank()) logFile = defaultLogFile;
        var tail = parseLogTail(text);
        var snippet = truncate("[" + logFile + "]\n" + tail);
        if (exitCode != 0) {
            return new ScriptOutcome(false, kind + ".sh exited with code " + exitCode, snippet);
        }
        return new ScriptOutcome(true, null, snippet);
    }

    private int parseExitCode(String output) {
        var raw = parseLineValue(output, EXIT_PREFIX);
        if (raw == null) return -1;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private String parseLineValue(String output, String prefix) {
        for (var line : output.split("\n")) {
            var trimmed = line.trim();
            if (trimmed.startsWith(prefix)) {
                return trimmed.substring(prefix.length()).trim();
            }
        }
        return null;
    }

    private String parseLogTail(String output) {
        var idx = output.indexOf(LOG_START_MARKER);
        if (idx < 0) return output.trim();
        return output.substring(idx + LOG_START_MARKER.length()).trim();
    }

    private String truncate(String value) {
        if (value == null || value.length() <= MAX_LOG_SNIPPET_CHARS) return value;
        return value.substring(value.length() - MAX_LOG_SNIPPET_CHARS);
    }

    private String extractRuntimeTaskId(ToolCallResult result) {
        if (result == null || !result.isPending()) return null;
        var stats = result.getStats();
        if (stats != null && stats.get("sandboxTaskId") instanceof String raw && !raw.isBlank()) {
            return raw;
        }
        return result.getTaskId();
    }

    private ToolCallResult runSyncBash(RuntimeClient client, String command) {
        var args = JsonUtil.toJson(Map.of("command", command, "timeout", SYNC_TIMEOUT_MS));
        return client.execute("run_bash_command", args);
    }

    private void sleepPoll() {
        try {
            Thread.sleep(pollIntervalMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("workspace bootstrap polling interrupted", e);
        }
    }

    @FunctionalInterface
    interface RuntimeClientFactory {
        RuntimeClient open(String host);
    }

    interface RuntimeClient extends AutoCloseable {
        ToolCallResult execute(String toolName, String arguments);

        ToolCallResult pollTask(String taskId);

        ToolCallResult cancelTask(String taskId);

        @Override
        void close();
    }

    private record ScriptOutcome(boolean succeeded, String errorMessage, String logSnippet) {
    }

    public static final class BootstrapFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public final String errorCode;
        public final transient WorkspaceBootstrapState state;

        public BootstrapFailure(String errorCode, String message, WorkspaceBootstrapState state) {
            super(message);
            this.errorCode = errorCode;
            this.state = state;
        }
    }

    private static final class SandboxClientAdapter implements RuntimeClient {
        private final SandboxClient delegate;

        private SandboxClientAdapter(SandboxClient delegate) {
            this.delegate = delegate;
        }

        @Override
        public ToolCallResult execute(String toolName, String arguments) {
            return delegate.execute(toolName, arguments, null);
        }

        @Override
        public ToolCallResult pollTask(String taskId) {
            return delegate.pollTask(taskId);
        }

        @Override
        public ToolCallResult cancelTask(String taskId) {
            return delegate.cancelTask(taskId);
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
