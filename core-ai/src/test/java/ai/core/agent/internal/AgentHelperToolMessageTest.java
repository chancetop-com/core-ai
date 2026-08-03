package ai.core.agent.internal;

import ai.core.agent.ExecutionContext;
import ai.core.llm.InputModality;
import ai.core.llm.ModalitySupport;
import ai.core.llm.ModelModalityRegistry;
import ai.core.llm.domain.Content;
import ai.core.llm.domain.FunctionCall;
import ai.core.llm.domain.RoleType;
import ai.core.tool.ToolCallResult;
import ai.core.tool.tools.GenerateImageTool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Xander
 */
class AgentHelperToolMessageTest {
    private static final ModelModalityRegistry IMAGE_UNSUPPORTED = (model, modality) ->
            modality == InputModality.TEXT ? ModalitySupport.SUPPORTED : ModalitySupport.UNSUPPORTED;
    private static final ModelModalityRegistry ALL_UNKNOWN = (model, modality) ->
            modality == InputModality.TEXT ? ModalitySupport.SUPPORTED : ModalitySupport.UNKNOWN;

    @Test
    void imageResultKeepsNativeImageWhenVisionNative() {
        var context = ExecutionContext.builder().sessionId("test").build();

        var message = AgentHelper.buildToolMessage(toolCall(), imageResult(), false, context);

        assertTrue(hasImagePart(message.content));
    }

    @Test
    void imageResultBecomesReferenceTextWhenNotVisionNative() {
        var context = ExecutionContext.builder().sessionId("test").build();
        context.setVisionNative(false);

        var message = AgentHelper.buildToolMessage(toolCall(), imageResult(), false, context);

        assertEquals(RoleType.TOOL, message.role);
        assertEquals("call-1", message.toolCallId);
        assertFalse(hasImagePart(message.content));
        assertEquals(1, message.content.size());
        assertTrue(message.content.getFirst().text.contains("caption_image"));
    }

    @Test
    void imageReferencePersistsViaSinkWhenAvailable() {
        var context = ExecutionContext.builder()
                .sessionId("test")
                .customVariable(GenerateImageTool.IMAGE_OUTPUT_SINK_CONTEXT_KEY,
                        (GenerateImageTool.ImageOutputSink) (fileName, contentType, bytes) -> "https://blob/tool-img.png")
                .build();
        context.setVisionNative(false);

        var message = AgentHelper.buildToolMessage(toolCall(), imageResult(), false, context);

        assertTrue(message.content.getFirst().text.contains("https://blob/tool-img.png"));
    }

    @Test
    void nullContextKeepsNativeImage() {
        var message = AgentHelper.buildToolMessage(toolCall(), imageResult(), false, null);

        assertTrue(hasImagePart(message.content));
    }

    @Test
    void textOnlyModelWithoutFallbackIsNotVisionNative() {
        assertFalse(AgentHelper.resolveVisionNative("deepseek-model", null, IMAGE_UNSUPPORTED));
    }

    @Test
    void textOnlyModelWithMultiModalFallbackIsVisionNative() {
        assertTrue(AgentHelper.resolveVisionNative("deepseek-model", "vision-model", ALL_UNKNOWN));
    }

    @Test
    void unknownModelIsTreatedAsVisionNative() {
        assertTrue(AgentHelper.resolveVisionNative("mystery-model", null, ALL_UNKNOWN));
    }

    @Test
    void captionToolHiddenForVisionNativeAgent() {
        var tools = java.util.List.of(tool("caption_image"), tool("read_file"));

        var filtered = AgentHelper.filterRedundantVisionTools(tools, true);

        assertEquals(java.util.List.of("read_file"), filtered.stream().map(t -> t.function.name).toList());
    }

    @Test
    void captionToolKeptForTextPathAgent() {
        var tools = java.util.List.of(tool("caption_image"), tool("read_file"));

        assertEquals(2, AgentHelper.filterRedundantVisionTools(tools, false).size());
    }

    private ai.core.llm.domain.Tool tool(String name) {
        var tool = new ai.core.llm.domain.Tool();
        tool.function = new ai.core.llm.domain.Function();
        tool.function.name = name;
        return tool;
    }

    private FunctionCall toolCall() {
        return FunctionCall.of("call-1", "function", "read_file", "{}");
    }

    private ToolCallResult imageResult() {
        return ToolCallResult.completed("image loaded").withImage("QUJD", "image/png");
    }

    private boolean hasImagePart(java.util.List<Content> content) {
        return content != null && content.stream().anyMatch(c -> c.type == Content.ContentType.IMAGE_URL);
    }
}
