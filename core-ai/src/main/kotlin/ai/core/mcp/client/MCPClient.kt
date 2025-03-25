package ai.core.mcp.client

import io.modelcontextprotocol.client.McpAsyncClient
import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport
import io.modelcontextprotocol.spec.McpSchema.*
import reactor.core.publisher.Mono
import java.time.Duration

/**
 * @author stephen
 */
object MCPClient {
    private const val MCP_CLIENT_NAME_PREFIX: String = "CoreAI-MCP-Client-"
    private const val MCP_VERSION: String = "1.0.0"
    private const val MCP_DEFAULT_TIMEOUT_IN_SECONDS: Long = 3

    fun connect(url: String): McpAsyncClient {
        val transport = HttpClientSseClientTransport(url)
        val builder = McpClient.async(transport)
        transport.connect {
            Mono.empty()
        }.block(Duration.ofSeconds(MCP_DEFAULT_TIMEOUT_IN_SECONDS))
        val client = builder.build()
        client.initialize().block(Duration.ofSeconds(MCP_DEFAULT_TIMEOUT_IN_SECONDS))
        return client;
    }

    fun listTools(client: McpAsyncClient): List<Tool> {
        return client.listTools().block(Duration.ofSeconds(MCP_DEFAULT_TIMEOUT_IN_SECONDS))?.tools ?: emptyList()
    }

    fun listPrompts(client: McpAsyncClient): List<Prompt> {
        return client.listPrompts().block(Duration.ofSeconds(MCP_DEFAULT_TIMEOUT_IN_SECONDS))?.prompts ?: emptyList()
    }

    fun listResources(client: McpAsyncClient): List<Resource> {
        return client.listResources().block(Duration.ofSeconds(MCP_DEFAULT_TIMEOUT_IN_SECONDS))?.resources ?: emptyList()
    }

    fun callTool(client: McpAsyncClient, request: CallToolRequest): Mono<CallToolResult> {
        return client.callTool(request)
    }
}