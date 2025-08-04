package ai.core.tool;

import ai.core.api.mcp.JsonRpcResponse;
import ai.core.api.mcp.schema.tool.ListToolsResult;
import ai.core.mcp.client.McpClientService;
import ai.core.tool.mcp.McpToolCalls;
import ai.core.utils.JsonUtil;
import core.framework.json.JSON;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;

/**
 * @author stephen
 */
class ToolCallTest {
    @Test
    void testBuildJsonSchema() {
        var mcpListToolResult = """
                {
                    "jsonrpc": "2.0",
                    "id": "0",
                    "result": {"tools":[{"name":"operation-assistant-api.MenuOperationWebService.updateMenu","description":"updateMenu","need_auth":null,"inputSchema":{"type":"object","description":null,"enum":null,"properties":{"name":{"type":"string","description":"name","enum":null,"properties":null,"required":null,"items":null,"format":null,"additionalProperties":null},"description":{"type":"string","description":"description","enum":null,"properties":null,"required":null,"items":null,"format":null,"additionalProperties":null},"id":{"type":"string","description":"id","enum":null,"properties":null,"required":null,"items":null,"format":null,"additionalProperties":null},"sections":{"type":"array","description":"sections","enum":null,"properties":null,"required":null,"items":{"type":"object","description":null,"enum":null,"properties":{"name":{"type":"string","description":"name","enum":null,"properties":null,"required":null,"items":null,"format":null,"additionalProperties":null},"item_ids":{"type":"array","description":"item_ids","enum":null,"properties":null,"required":null,"items":{"type":"string","description":null,"enum":null,"properties":null,"required":null,"items":null,"format":null,"additionalProperties":null},"format":null,"additionalProperties":null},"id":{"type":"string","description":"id","enum":null,"properties":null,"required":null,"items":null,"format":null,"additionalProperties":null},"sort_order":{"type":"integer","description":"sort_order","enum":null,"properties":null,"required":null,"items":null,"format":null,"additionalProperties":null}},"required":["name","sort_order","item_ids"],"items":null,"format":null,"additionalProperties":null},"format":null,"additionalProperties":null},"operator":{"type":"string","description":"operator","enum":null,"properties":null,"required":null,"items":null,"format":null,"additionalProperties":null}},"required":["id","name","sections","operator"],"items":null,"format":null,"additionalProperties":null}}],"nextCursor":null},
                    "error": null
                }
                """;
        var mockMcpClientService = Mockito.mock(McpClientService.class);
        var rpcRsp = JSON.fromJSON(JsonRpcResponse.class, mcpListToolResult);
        var tools = JsonUtil.fromJson(ListToolsResult.class, (Map<?, ?>) rpcRsp.result).tools;
        Mockito.when(mockMcpClientService.listTools()).thenReturn(tools);

        var toolCalls = McpToolCalls.from(mockMcpClientService);
        var toolCall = toolCalls.getFirst();
        var jsonSchema = toolCall.toJsonSchema();
        var expect = """
                {"type":"object","properties":{"name":{"type":"string","description":"name"},"description":{"type":"string","description":"description"},"id":{"type":"string","description":"id"},"sections":{"type":"array","description":"sections","items":{"type":"object","properties":{"name":{"type":"string","description":"name"},"item_ids":{"type":"array","description":"item_ids","items":{"type":"string"}},"id":{"type":"string","description":"id"},"sort_order":{"type":"integer","description":"sort_order"}},"required":["name","item_ids","sort_order"]}},"operator":{"type":"string","description":"operator"}},"required":["name","id","sections","operator"]}""";
        var json = JsonUtil.toJson(jsonSchema);
        assert json.equals(expect);
    }

    @Test
    void testBuildJsonSchemaEnum() {
        var mcpListToolResult = """
                {
                    "jsonrpc": "2.0",
                    "id": "0",
                    "result": {"tools":[{"name":"ProductOperationWebService_searchMerchantProduct","description":"searchMerchantProduct","need_auth":null,"inputSchema":{"type":"object","description":null,"enum":null,"properties":{"exclude_category_id":{"type":"string","description":"exclude_category_id","enum":null,"properties":null,"required":null,"items":null,"format":null,"additionalProperties":null},"is_asc":{"type":"boolean","description":"is_asc","enum":null,"properties":null,"required":null,"items":null,"format":null,"additionalProperties":null},"category_id":{"type":"string","description":"category_id","enum":null,"properties":null,"required":null,"items":null,"format":null,"additionalProperties":null},"no_category":{"type":"boolean","description":"no_category","enum":null,"properties":null,"required":null,"items":null,"format":null,"additionalProperties":null},"limit":{"type":"integer","description":"limit","enum":null,"properties":null,"required":null,"items":null,"format":null,"additionalProperties":null},"fuzzy_name":{"type":"string","description":"fuzzy_name","enum":null,"properties":null,"required":null,"items":null,"format":null,"additionalProperties":null},"exclude_product_ids":{"type":"array","description":"exclude_product_ids","enum":null,"properties":null,"required":null,"items":{"type":"string","description":null,"enum":null,"properties":null,"required":null,"items":null,"format":null,"additionalProperties":null},"format":null,"additionalProperties":null},"skip":{"type":"integer","description":"skip","enum":null,"properties":null,"required":null,"items":null,"format":null,"additionalProperties":null},"merchant_id":{"type":"string","description":"merchant_id","enum":null,"properties":null,"required":null,"items":null,"format":null,"additionalProperties":null},"sort_by":{"type":"string","description":"SearchMerchantProductOperationRequest$SortRule","enum":["NAME","CATEGORY","PRICE"],"properties":null,"required":null,"items":null,"format":null,"additionalProperties":null},"status":{"type":"string","description":"SearchMerchantProductOperationRequest","enum":["ACTIVE","INACTIVE"],"properties":null,"required":null,"items":null,"format":null,"additionalProperties":null}},"required":["merchant_id","no_category","sort_by","is_asc","skip","limit"],"items":null,"format":null,"additionalProperties":null}}],"nextCursor":null},
                    "error": null
                }
                """;
        var mockMcpClientService = Mockito.mock(McpClientService.class);
        var rpcRsp = JSON.fromJSON(JsonRpcResponse.class, mcpListToolResult);
        var tools = JsonUtil.fromJson(ListToolsResult.class, (Map<?, ?>) rpcRsp.result).tools;
        Mockito.when(mockMcpClientService.listTools()).thenReturn(tools);

        var toolCalls = McpToolCalls.from(mockMcpClientService);
        var toolCall = toolCalls.getFirst();
        var jsonSchema = toolCall.toJsonSchema();
        var expect = """
                {"type":"object","properties":{"exclude_category_id":{"type":"string","description":"exclude_category_id"},"is_asc":{"type":"boolean","description":"is_asc"},"category_id":{"type":"string","description":"category_id"},"no_category":{"type":"boolean","description":"no_category"},"limit":{"type":"integer","description":"limit"},"fuzzy_name":{"type":"string","description":"fuzzy_name"},"exclude_product_ids":{"type":"array","description":"exclude_product_ids","items":{"type":"string"}},"skip":{"type":"integer","description":"skip"},"merchant_id":{"type":"string","description":"merchant_id"},"sort_by":{"type":"string","description":"SearchMerchantProductOperationRequest$SortRule","enum":["NAME","CATEGORY","PRICE"]},"status":{"type":"string","description":"SearchMerchantProductOperationRequest","enum":["ACTIVE","INACTIVE"]}},"required":["is_asc","no_category","limit","skip","merchant_id","sort_by"]}""";
        var json = JsonUtil.toJson(jsonSchema);
        assert json.equals(expect);
    }
}