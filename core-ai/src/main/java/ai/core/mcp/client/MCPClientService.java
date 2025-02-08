package ai.core.mcp.client;

import core.framework.json.JSON;
import core.framework.util.Strings;
import io.modelcontextprotocol.kotlin.sdk.CallToolResult;
import io.modelcontextprotocol.kotlin.sdk.Prompt;
import io.modelcontextprotocol.kotlin.sdk.Resource;
import io.modelcontextprotocol.kotlin.sdk.TextContent;
import io.modelcontextprotocol.kotlin.sdk.Tool;
import io.modelcontextprotocol.kotlin.sdk.client.Client;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.EmptyCoroutineContext;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

/**
 * @author stephen
 */
public class MCPClientService {
    private static final Logger LOGGER = LoggerFactory.getLogger(MCPClientService.class);
    private final MCPServerConfig config;
    private Client client;

    public MCPClientService(MCPServerConfig config) {
        this.config = config;
    }

    @SuppressWarnings("unchecked")
    public void callTool(String name, String params, MCPToolCallMessageHandler mcpToolCallMessageHandler) {
        if (client == null) {
            client = connect(config.host(), config.port());
        }

        var argsMap = JSON.fromJSON(Map.class, params);
        MCPClient.INSTANCE.callTool(client, name, argsMap, new Continuation<>() {
            @Override
            public @NotNull CoroutineContext getContext() {
                return EmptyCoroutineContext.INSTANCE;
            }
            @Override
            public void resumeWith(@NotNull Object result) {
                if (!(result instanceof CallToolResult)) {
                    LOGGER.error("Failed to call tool: {}, {}", name, params);
                    return;
                }
                mcpToolCallMessageHandler.resultHandler(toTextContentString(result));
            }
        });
    }

    private String toTextContentString(Object result) {
        return ((CallToolResult) result).getContent().stream().map(v -> {
            if (TextContent.TYPE.equals(v.getType())) {
                return ((TextContent) v).getText();
            }
            return v.toString();
        }).collect(Collectors.joining("\n"));
    }

    @SuppressWarnings("unchecked")
    public List<Tool> listTools() {
        if (client == null) {
            client = connect(config.host(), config.port());
        }
        var future = new CompletableFuture<List<Tool>>();

        MCPClient.INSTANCE.listTools(client, new Continuation<>() {
            @Override
            public @NotNull CoroutineContext getContext() {
                return EmptyCoroutineContext.INSTANCE;
            }

            @Override
            public void resumeWith(@NotNull Object result) {
                if (!(result instanceof List<?>)) {
                    LOGGER.error("Failed to list tools");
                    return;
                }
                future.complete((List<Tool>) result);
            }
        });

        try {
            return future.get();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    public List<Prompt> listPrompts() {
        if (client == null) {
            client = connect(config.host(), config.port());
        }
        var future = new CompletableFuture<List<Prompt>>();

        MCPClient.INSTANCE.listPrompts(client, new Continuation<>() {
            @Override
            public @NotNull CoroutineContext getContext() {
                return EmptyCoroutineContext.INSTANCE;
            }

            @Override
            public void resumeWith(@NotNull Object result) {
                if (!(result instanceof List<?>)) {
                    LOGGER.error("Failed to list prompts");
                    return;
                }
                future.complete((List<Prompt>) result);
            }
        });

        try {
            return future.get();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    public List<Resource> listResources() {
        if (client == null) {
            client = connect(config.host(), config.port());
        }
        var future = new CompletableFuture<List<Resource>>();

        MCPClient.INSTANCE.listResources(client, new Continuation<>() {
            @Override
            public @NotNull CoroutineContext getContext() {
                return EmptyCoroutineContext.INSTANCE;
            }

            @Override
            public void resumeWith(@NotNull Object result) {
                if (!(result instanceof List<?>)) {
                    LOGGER.error("Failed to list resources");
                    return;
                }
                future.complete((List<Resource>) result);
            }
        });

        try {
            return future.get();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    public Client connect(String host, int port) {
        var future = new CompletableFuture<Client>();

        MCPClient.INSTANCE.connect(host, port, new Continuation<>() {
            @Override
            public @NotNull CoroutineContext getContext() {
                return EmptyCoroutineContext.INSTANCE;
            }
            @Override
            public void resumeWith(@NotNull Object result) {
                if (!(result instanceof Client)) {
                    future.completeExceptionally(new RuntimeException(Strings.format("Failed to connect to MCP server: {}:{}", host, port)));
                    return;
                }
                LOGGER.info("Connected to MCP server: {}:{}", host, port);
                future.complete((Client) result);
            }
        });

        try {
            return future.get();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    public void close() {
        if (client != null) {
            client.close(new Continuation<>() {
                @Override
                public @NotNull CoroutineContext getContext() {
                    return EmptyCoroutineContext.INSTANCE;
                }

                @Override
                public void resumeWith(@NotNull Object o) {

                }
            });
        }
    }
}
