package ai.core.mcp.client


import core.framework.internal.log.LogManager
import io.ktor.client.*
import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.Prompt
import io.modelcontextprotocol.kotlin.sdk.Resource
import io.modelcontextprotocol.kotlin.sdk.Tool
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.mcpSseTransport

/**
 * @author stephen
 */
object MCPClient {
    private const val MCP_CLIENT_NAME_PREFIX: String = "CoreAI-MCP-Client-"
    private const val MCP_VERSION: String = "1.0.0"

    suspend fun connect(h: String, p: Int): Client {
        val client = Client(Implementation(MCP_CLIENT_NAME_PREFIX + LogManager.APP_NAME, MCP_VERSION))
        val transport = HttpClient {
            install(io.ktor.client.plugins.sse.SSE)
        }.mcpSseTransport {
            url {
                host = h
                port = p
            }
        }
        client.connect(transport)
        return client
    }

    suspend fun listTools(client: Client): List<Tool> {
        return client.listTools()?.tools ?: emptyList()
    }

    suspend fun listPrompts(client: Client): List<Prompt> {
        return client.listPrompts()?.prompts ?: emptyList()
    }

    suspend fun listResources(client: Client): List<Resource> {
        return client.listResources()?.resources ?: emptyList()
    }

    suspend fun callTool(client: Client, tool: String, args: Map<String, String>) {
        client.callTool(tool, args)
    }
}