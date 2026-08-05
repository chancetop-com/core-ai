package ai.core.server.llmcall;

import ai.core.api.server.run.LLMCallRequest;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.DefinitionType;
import ai.core.server.run.LLMCallExecutor;
import ai.core.tool.ToolCallResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LLMCallToolTest {
    @Test
    void executesWithQueryAndStats() {
        var definition = llmCallDefinition();
        var executor = new StubExecutor();
        var tool = LLMCallTool.create(definition, executor);

        var result = tool.execute("{\"query\":\"recognize this menu\"}");

        assertEquals(ToolCallResult.Status.COMPLETED, result.getStatus());
        assertEquals("{\"status\":\"ok\"}", result.getResult());
        assertEquals("recognize this menu", executor.capturedInput);
        assertNull(executor.capturedAttachments);
        assertEquals(10L, result.getStats().get("llm_call_input_tokens"));
        assertEquals(20L, result.getStats().get("llm_call_output_tokens"));
    }

    @Test
    void wrapsQueryWithInputTemplatePlaceholder() {
        var definition = llmCallDefinition();
        definition.inputTemplate = "Extract the menu from this input: {{query}}";
        var executor = new StubExecutor();
        var tool = LLMCallTool.create(definition, executor);

        tool.execute("{\"query\":\"https://example.com/menu.jpg\"}");

        assertEquals("Extract the menu from this input: https://example.com/menu.jpg", executor.capturedInput);
    }

    @Test
    void appendsQueryWhenTemplateHasNoPlaceholder() {
        var definition = llmCallDefinition();
        definition.inputTemplate = "Always answer in Chinese";
        var executor = new StubExecutor();
        var tool = LLMCallTool.create(definition, executor);

        tool.execute("{\"query\":\"hello\"}");

        assertEquals("Always answer in Chinese\nhello", executor.capturedInput);
    }

    @Test
    void missingQueryReturnsFailed() {
        var tool = LLMCallTool.create(llmCallDefinition(), new StubExecutor());

        var result = tool.execute("{\"image_url\":\"https://example.com/a.jpg\"}");

        assertEquals(ToolCallResult.Status.FAILED, result.getStatus());
        assertTrue(result.getResult().contains("'query' is required"));
    }

    @Test
    void imageUrlBecomesImageAttachment() {
        var definition = llmCallDefinition();
        var executor = new StubExecutor();
        var tool = LLMCallTool.create(definition, executor);

        tool.execute("{\"query\":\"analyze\",\"image_url\":\"https://example.com/a.jpg\"}");

        assertEquals(1, executor.capturedAttachments.size());
        assertEquals("https://example.com/a.jpg", executor.capturedAttachments.getFirst().url);
        assertEquals(LLMCallRequest.AttachmentType.IMAGE, executor.capturedAttachments.getFirst().type);
    }

    @Test
    void sanitizesDefinitionNameAsToolName() {
        var definition = llmCallDefinition();
        definition.name = "Menu Recognizer/2.0";
        var tool = LLMCallTool.create(definition, new StubExecutor());

        assertEquals("Menu-Recognizer-2.0", tool.getName());
    }

    @Test
    void timeoutFollowsDefinition() {
        var definition = llmCallDefinition();
        definition.timeoutSeconds = 30;
        var tool = LLMCallTool.create(definition, new StubExecutor());

        assertEquals(30_000L, tool.getTimeoutMs());
    }

    @Test
    void rejectsNonLlmCallDefinition() {
        var definition = llmCallDefinition();
        definition.type = DefinitionType.AGENT;

        assertThrows(IllegalArgumentException.class, () -> LLMCallTool.create(definition, new StubExecutor()));
    }

    private AgentDefinition llmCallDefinition() {
        var definition = new AgentDefinition();
        definition.id = "def1";
        definition.name = "image_recognition";
        definition.description = "Recognize menu from image";
        definition.type = DefinitionType.LLM_CALL;
        return definition;
    }

    private static final class StubExecutor extends LLMCallExecutor {
        String capturedInput;
        List<LLMCallRequest.Attachment> capturedAttachments;

        @Override
        public Result execute(AgentDefinition definition, String input, List<LLMCallRequest.Attachment> attachments) {
            capturedInput = input;
            capturedAttachments = attachments;
            return new Result("{\"status\":\"ok\"}", 10, 20);
        }
    }
}
