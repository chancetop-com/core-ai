package ai.core.sandbox;

import ai.core.tool.tools.EditFileTool;
import ai.core.tool.tools.GlobFileTool;
import ai.core.tool.tools.GrepFileTool;
import ai.core.tool.tools.PythonScriptTool;
import ai.core.tool.tools.ReadFileTool;
import ai.core.tool.tools.ShellCommandTool;
import ai.core.tool.tools.WriteFileTool;

import java.util.Set;

/**
 * @author stephen
 */
public final class SandboxConstants {
    public static final Set<String> INTERCEPTED_TOOLS = Set.of(
            ShellCommandTool.TOOL_NAME,
            PythonScriptTool.TOOL_NAME,
            ReadFileTool.TOOL_NAME,
            EditFileTool.TOOL_NAME,
            WriteFileTool.TOOL_NAME,
            GlobFileTool.TOOL_NAME,
            GrepFileTool.TOOL_NAME
    );

    public static final String DEFAULT_IMAGE = "chancetop/core-ai-sandbox-runtime:latest";

    public static final int DEFAULT_MEMORY_LIMIT_MB = 512;

    public static final int MAX_MEMORY_LIMIT_MB = 2048;

    public static final int DEFAULT_CPU_LIMIT_MILLICORES = 500;

    public static final int MAX_CPU_LIMIT_MILLICORES = 2000;

    public static final int DEFAULT_TIMEOUT_SECONDS = 1800;

    public static final int MAX_TIMEOUT_SECONDS = 86_400;

    public static final String DEFAULT_TMP_SIZE_LIMIT = "512Mi";

    public static final long DEFAULT_TOOL_TIMEOUT_MS = 120_000;

    public static final long MAX_TOOL_TIMEOUT_MS = 600_000;

    // Per-request timeout for /execute HTTP calls, independent of sandbox container TTL.
    // A long-running command (e.g. sleep 300) should not be killed by the HTTP client
    // timeout when the sandbox container itself has a short lifetime TTL.
    public static final int REQUEST_TIMEOUT_SECONDS = 600;

    public static final int MAX_OUTPUT_SIZE = 30 * 1024;

    public static final int RUNTIME_PORT = 8080;

    public static final int DEFAULT_MAX_ASYNC_TASKS = 5;

    public static final int DEFAULT_HEALTH_CHECK_TIMEOUT_SECONDS = 60;

    // HTTP timeout for MCP server start-up calls (startMcpServer). Must be long enough
    // to cover the sandbox runtime's readiness probe (120 s) plus network / serialisation
    // overhead. Once the server is ready the normal tool / request timeouts apply.
    public static final int MCP_STARTUP_TIMEOUT_SECONDS = 180;

    // Per-server timeout used during session creation (POST /api/sessions). Sessions
    // should not block for the full 180s per MCP server — use a shorter timeout so
    // that a stuck MCP server does not cause session creation to take minutes.
    public static final int SESSION_MCP_STARTUP_TIMEOUT_SECONDS = 30;

    private SandboxConstants() {
    }
}
