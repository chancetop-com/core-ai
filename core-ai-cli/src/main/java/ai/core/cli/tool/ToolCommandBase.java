package ai.core.cli.tool;

import ai.core.api.server.sandboxhub.SandboxHubCallResponse;
import ai.core.api.server.sandboxhub.SandboxHubContentPart;
import ai.core.api.server.sandboxhub.SandboxHubLlmUsage;
import ai.core.cli.ConsoleWriter;
import ai.core.cli.hub.HubCliError;
import ai.core.cli.hub.HubCommandBase;
import ai.core.cli.hub.HubCredentialResolver;
import ai.core.cli.hub.HubExitCodes;
import ai.core.cli.hub.HubRenderer;
import ai.core.llm.domain.Usage;
import ai.core.tool.ToolCallResult;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * Shared plumbing for {@code core-ai-cli tool ...}: load the local tool runtime once, map a {@link ToolCallResult}
 * onto the same {@link SandboxHubCallResponse} shape the sandbox hub and the Python SDK speak.
 *
 * @author stephen
 */
abstract class ToolCommandBase extends HubCommandBase {
    protected static final String ENV_SESSION_ID = "CORE_AI_SESSION_ID";

    static SandboxHubCallResponse response(ToolCallResult result, String callId, long durationMs) {
        var response = new SandboxHubCallResponse();
        response.callId = result.isPending() && result.getTaskId() != null ? result.getTaskId() : callId;
        response.durationMs = durationMs;
        response.text = result.getResult();
        response.content = List.<SandboxHubContentPart>of();
        if (result.isPending()) {
            response.status = "pending";
            response.taskId = result.getTaskId();
            return response;
        }
        if (result.isFailed()) {
            response.status = "failed";
            response.success = Boolean.FALSE;
            response.isError = Boolean.TRUE;
            response.errorMessage = result.getResult();
        } else {
            response.status = "completed";
            response.success = Boolean.TRUE;
            response.isError = Boolean.FALSE;
        }
        var usage = result.getLlmUsage();
        if (usage != null || result.getLlmModel() != null) {
            response.llmUsage = llmUsage(result.getLlmModel(), usage, result.getLlmCostUsd());
        }
        return response;
    }

    private static SandboxHubLlmUsage llmUsage(String model, Usage usage, Double cost) {
        var view = new SandboxHubLlmUsage();
        view.model = model;
        if (usage != null) {
            view.inputTokens = (long) usage.getPromptTokens();
            view.outputTokens = (long) usage.getCompletionTokens();
        }
        view.cost = cost;
        return view;
    }

    @Option(names = "--config", description = "Config file path (default: ~/.core-ai/agent.properties)")
    Path configFile;

    @Option(names = "--workspace", description = "Workspace for generated media and workspace config (default: current dir)")
    Path workspace;

    private LocalToolRuntime runtime;

    protected LocalToolRuntime runtime() {
        if (runtime == null) runtime = LocalToolRuntime.load(workspaceDirectory(), credentialsOrNull(), configFile);
        return runtime;
    }

    /** Credentials are required only by {@code understand_video}; the media tools work from a custom provider too. */
    protected HubCredentialResolver.HubCredentials credentialsOrNull() {
        try {
            return credentials();
        } catch (HubCliError e) {
            return null;
        }
    }

    protected Path workspaceDirectory() {
        return workspace != null ? workspace.toAbsolutePath() : Path.of("").toAbsolutePath();
    }

    protected String sessionId(String requested) {
        var value = requested == null || requested.isBlank() ? System.getenv(ENV_SESSION_ID) : requested;
        return value == null ? "" : value.trim();
    }

    protected int timeoutSeconds(LocalToolRuntime.LocalBuiltinTool builtin, Integer requested) {
        var seconds = requested == null ? builtin.timeoutSeconds() : requested;
        return Math.max(1, Math.min(seconds, LocalToolRuntime.MAX_TIMEOUT_SECONDS));
    }

    protected int print(SandboxHubCallResponse response) {
        if (json()) {
            HubRenderer.printJson(response);
        } else if (raw()) {
            printText(response.text);
        } else {
            printText(response.text);
            metadata("status: " + response.status + ", duration: " + response.durationMs + "ms");
        }
        return Boolean.TRUE.equals(response.isError) ? HubExitCodes.TOOL_ERROR : HubExitCodes.SUCCESS;
    }

    private void printText(String text) {
        if (text == null || text.isEmpty()) return;
        ConsoleWriter.print(text);
        if (!text.endsWith("\n")) ConsoleWriter.println();
    }

    protected String newCallId() {
        return UUID.randomUUID().toString();
    }
}
