package ai.core.sandbox;

import ai.core.agent.ExecutionContext;
import ai.core.tool.ToolCallResult;

import java.util.List;
import java.util.Map;

/**
 * Standard sandbox abstraction — every sandbox backend implements this interface.
 * <p>
 * The caller (ToolExecutor, CodeExecutor, MCP manager, skill tool, etc.) works against
 * this contract only. Switching the underlying provider (Docker/K8s, E2B, CubeSandbox)
 * is a configuration change with zero caller-side modifications.
 *
 * @author stephen
 */
public interface Sandbox extends AutoCloseable {

    boolean shouldIntercept(String toolName);

    ToolCallResult execute(String toolName, String arguments, ExecutionContext context);

    SandboxStatus getStatus();

    String getId();

    /** Returns the human-readable hostname of this sandbox (e.g. pod name, container id). */
    String hostname();

    /** Materialize a skill archive into the sandbox filesystem. */
    void materializeSkill(String name, String version, byte[] tarBytes);

    /** Download a file from the sandbox filesystem. */
    SandboxFile downloadFile(String path);

    /** Upload file content to the sandbox at the specified path. */
    void uploadFile(String path, byte[] content);

    String ip();

    int port();

    String image();

    // ---- MCP server lifecycle (started inside the sandbox, bridged through the runtime) ----

    /** Start an MCP server process inside the sandbox. The returned id can be used to
     *  construct the bridge endpoint via {@link #getMcpEndpoint()} with header {@code X-Mcp-Server-Id}. */
    String startMcpServer(String id, String command, List<String> args, Map<String, String> env, int timeoutSeconds);

    /** Stop an MCP server process previously started via {@link #startMcpServer}. */
    void stopMcpServer(String id);

    /** Returns the base endpoint for the runtime's MCP JSON-RPC bridge, e.g. {@code http://ip:port/mcp}. */
    String getMcpEndpoint();

    @Override
    void close();
}
