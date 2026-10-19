package ai.core.cli.appserver;

import ai.core.bootstrap.PropertiesFileSource;
import ai.core.cli.media.MediaModelCatalog;
import ai.core.media.CoreAiGatewayMediaProvider;
import ai.core.media.MediaProvider;
import ai.core.tool.tools.MediaModelHint;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @author stephen
 */
class MediaSettingsApiTest {
    @TempDir
    Path temp;

    private Path global;
    private Path workspace;

    @BeforeEach
    void setUp() throws IOException {
        global = temp.resolve("agent.properties");
        workspace = temp.resolve("workspace");
        Files.createDirectories(workspace.resolve(".core-ai"));
    }

    @Test
    void reportsSourcesAndWorkspaceOverridesPerModality() throws IOException {
        var props = new PropertiesFileSource(new Properties());
        props.putProperty("media.image.api.base", "https://images.example.com/v1");
        Files.writeString(global, "media.image.model=custom-image\nmedia.video.model=custom-video\n",
                StandardCharsets.UTF_8);
        Files.writeString(workspace.resolve(".core-ai").resolve("agent.properties"), "media.video.model=workspace-video\n",
                StandardCharsets.UTF_8);
        var api = new MediaSettingsApi(props, workspace, mock(MediaProvider.class), null, null, global);

        var result = api.get();

        assertEquals("custom", result.path("imageSource").asText());
        assertEquals("none", result.path("videoSource").asText());
        assertEquals("custom-image", result.path("imageModel").asText());
        assertEquals("custom-video", result.path("videoModel").asText());
        assertEquals("workspace-video", result.path("effectiveVideoModel").asText());
        assertFalse(result.path("imageModelFromWorkspace").asBoolean());
        assertTrue(result.path("videoModelFromWorkspace").asBoolean());
    }

    @Test
    void setWritesAndRemovesTheSharedDefaultModels() throws IOException {
        var api = new MediaSettingsApi(new PropertiesFileSource(new Properties()), workspace, null, null, null, global);

        var applied = api.set(params("imageModel", "image-a", "videoModel", "video-a"));

        assertTrue(applied.path("restartRequired").asBoolean());
        var stored = load(global);
        assertEquals("image-a", stored.getProperty("media.image.model"));
        assertEquals("video-a", stored.getProperty("media.video.model"));

        var cleared = Params.object();
        cleared.putNull("imageModel");
        api.set(cleared);

        var after = load(global);
        assertFalse(after.containsKey("media.image.model"));
        assertEquals("video-a", after.getProperty("media.video.model"));
    }

    @Test
    void rejectsAModelTheManagedGatewayDoesNotEnable() {
        var catalog = mock(MediaModelCatalog.class);
        when(catalog.hasSnapshot()).thenReturn(true);
        when(catalog.imageModels()).thenReturn(List.of(new MediaModelHint("allowed", "allowed-upstream", "p")));
        when(catalog.defaultImageModel()).thenReturn("allowed");
        var provider = new CoreAiGatewayMediaProvider("https://core-ai.example.com/api/gateway/v1", "key");
        var api = new MediaSettingsApi(new PropertiesFileSource(new Properties()), workspace, provider, null, catalog, global);

        var exception = assertThrows(RpcException.class, () -> api.set(params("imageModel", "unknown")));

        assertTrue(exception.getMessage().contains("not enabled"), exception.getMessage());
        assertFalse(Files.exists(global), "a rejected value must not be persisted");
    }

    @Test
    void acceptsAModelTheManagedGatewayEnables() {
        var catalog = mock(MediaModelCatalog.class);
        when(catalog.hasSnapshot()).thenReturn(true);
        when(catalog.imageModels()).thenReturn(List.of(new MediaModelHint("allowed", "allowed-upstream", "p")));
        var provider = new CoreAiGatewayMediaProvider("https://core-ai.example.com/api/gateway/v1", "key");
        var api = new MediaSettingsApi(new PropertiesFileSource(new Properties()), workspace, provider, null, catalog, global);

        var result = api.set(params("imageModel", "allowed"));

        assertEquals("allowed", result.path("imageModel").asText());
    }

    private ObjectNode params(String... keyValues) {
        var node = Params.object();
        for (var i = 0; i < keyValues.length; i += 2) {
            node.put(keyValues[i], keyValues[i + 1]);
        }
        return node;
    }

    private Properties load(Path file) throws IOException {
        var props = new Properties();
        if (Files.exists(file)) {
            try (var in = Files.newInputStream(file)) {
                props.load(in);
            }
        }
        return props;
    }
}
