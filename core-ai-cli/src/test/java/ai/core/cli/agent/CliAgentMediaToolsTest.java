package ai.core.cli.agent;

import ai.core.cli.media.MediaModelCatalog;
import ai.core.llm.providers.LiteLLMMediaProvider;
import ai.core.media.CoreAiGatewayMediaProvider;
import ai.core.media.MediaProvider;
import ai.core.tool.ToolCall;
import ai.core.tool.tools.MediaModelHint;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @author stephen
 */
class CliAgentMediaToolsTest {
    @Test
    void aManagedGatewayWithoutAModelRegistersNoMediaTool() {
        var tools = CliAgent.mediaTools(config(gateway(), null, null, null, null));

        assertTrue(tools.isEmpty(), "a tool the server would reject must not be visible");
    }

    @Test
    void anExplicitModelMakesTheToolAvailableWithoutACatalog() {
        var tools = CliAgent.mediaTools(config(gateway(), null, "gpt-image-2.5", null, null));

        assertTrue(names(tools).contains("generate_image"));
        assertFalse(names(tools).contains("generate_video"));
        assertTrue(tool(tools, "generate_image").getDescription().contains("Default image model for this session: gpt-image-2.5"));
    }

    @Test
    void theCatalogRegistersBothModalitiesAndTheirDefaults() {
        var catalog = mock(MediaModelCatalog.class);
        when(catalog.imageModels()).thenReturn(List.of(new MediaModelHint("gpt-image-2.5", "gpt-image-2.5", "azure", "GPT Image 2.5")));
        when(catalog.imageEditModels()).thenReturn(List.of(new MediaModelHint("gpt-image-2.5-edit", "gpt-image-2.5", "azure", "Edit")));
        when(catalog.videoModels()).thenReturn(List.of(new MediaModelHint("seedance-2.5", "doubao-seedance-2-5", "volcengine", "Seedance 2.5")));
        when(catalog.defaultImageModel()).thenReturn("gpt-image-2.5");
        when(catalog.defaultVideoModel()).thenReturn("seedance-2.5");

        var tools = CliAgent.mediaTools(config(gateway(), gateway(), null, null, catalog));

        assertTrue(names(tools).containsAll(List.of("generate_image", "generate_video", "get_video_status")));
        assertTrue(tool(tools, "generate_image").getDescription().contains("gpt-image-2.5"));
        assertTrue(tool(tools, "generate_video").getDescription().contains("Default video model for this session: seedance-2.5"));
    }

    @Test
    void aVideoOnlyEndpointDoesNotRegisterGenerateImage() {
        var catalog = mock(MediaModelCatalog.class);
        when(catalog.videoModels()).thenReturn(List.of(new MediaModelHint("seedance-2.5", "doubao-seedance-2-5", "volcengine", null)));
        when(catalog.defaultVideoModel()).thenReturn("seedance-2.5");

        var tools = CliAgent.mediaTools(config(null, gateway(), null, null, catalog));

        assertFalse(names(tools).contains("generate_image"), "a video provider must never be used as an image provider");
        assertTrue(names(tools).contains("generate_video"));
    }

    @Test
    void aCustomEndpointKeepsTheStaticToolWithoutAModel() {
        var custom = new LiteLLMMediaProvider("https://images.example.com/v1", "key");

        var tools = CliAgent.mediaTools(config(custom, null, null, null, null));

        assertEquals(List.of("generate_image"), names(tools));
        assertFalse(tool(tools, "generate_image").getDescription().contains("Default image model"),
                "a custom endpoint has no server catalog to name a default from");
    }

    private List<String> names(List<ToolCall> tools) {
        return tools.stream().map(ToolCall::getName).toList();
    }

    private ToolCall tool(List<ToolCall> tools, String name) {
        return tools.stream().filter(tool -> name.equals(tool.getName())).findFirst().orElseThrow();
    }

    private MediaProvider gateway() {
        return new CoreAiGatewayMediaProvider("https://core-ai.example.com/api/gateway/v1", "key");
    }

    private CliAgent.Config config(MediaProvider imageProvider, MediaProvider videoProvider,
                                   String imageModel, String videoModel, MediaModelCatalog catalog) {
        return new CliAgent.Config(null, null, 0, null, Path.of("."), null, false, false, false, false,
                "session-1", List.of(), List.of(), Map.of(), false, null, imageProvider, videoProvider,
                imageModel, videoModel, null, null, null, "cli", List.of(), catalog, null, Set.of());
    }
}
