package ai.core.mcp.server;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpStatelessServerHandler;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStatelessServerTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * @author stephen
 */
public class McpStreamableServerTransportProvider implements McpStatelessServerTransport {
    private static final Logger LOGGER = LoggerFactory.getLogger(McpStreamableServerTransportProvider.class);

    private McpStatelessServerHandler mcpHandler;
    private volatile boolean isClosing = false;

    @Override
    public void setMcpHandler(McpStatelessServerHandler mcpHandler) {
        this.mcpHandler = mcpHandler;
        LOGGER.info("MCP handler set for stateless transport");
    }

    @Override
    public Mono<Void> closeGracefully() {
        isClosing = true;
        LOGGER.info("Closing stateless MCP transport");
        return Mono.empty();
    }

    public McpSchema.JSONRPCResponse handleRequest(McpSchema.JSONRPCRequest request) {
        if (isClosing) {
            return createErrorResponse(request.id(), "Server is shutting down");
        }

        if (mcpHandler == null) {
            return createErrorResponse(request.id(), "Handler not initialized");
        }

        try {
            // Use empty transport context
            return mcpHandler.handleRequest(McpTransportContext.EMPTY, request).block();
        } catch (Exception e) {
            LOGGER.error("Error handling MCP request: {}", request.method(), e);
            return createErrorResponse(request.id(), e.getMessage());
        }
    }

    public void handleNotification(McpSchema.JSONRPCNotification notification) {
        if (isClosing) {
            LOGGER.warn("Ignoring notification, server is shutting down");
            return;
        }

        if (mcpHandler == null) {
            LOGGER.warn("Handler not initialized, ignoring notification");
            return;
        }

        try {
            // Use empty transport context
            mcpHandler.handleNotification(McpTransportContext.EMPTY, notification).block();
        } catch (Exception e) {
            LOGGER.error("Error handling MCP notification: {}", notification.method(), e);
        }
    }

    public boolean isReady() {
        return mcpHandler != null && !isClosing;
    }

    private McpSchema.JSONRPCResponse createErrorResponse(Object id, String message) {
        return new McpSchema.JSONRPCResponse(
            McpSchema.JSONRPC_VERSION,
            id,
            null,
            new McpSchema.JSONRPCResponse.JSONRPCError(-32603, message, null)
        );
    }
}
