package ai.core.cli.appserver;

import ai.core.bootstrap.PropertiesFileSource;
import ai.core.cli.CliAppHelper;
import ai.core.cli.auth.AuthConfig;
import ai.core.cli.media.MediaModelCatalog;
import ai.core.media.CoreAiGatewayMediaProvider;
import ai.core.media.MediaProvider;
import ai.core.tool.tools.MediaModelHint;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

/**
 * Settings surface for media generation. The choices live in the shared {@code ~/.core-ai/agent.properties}
 * so the desktop and the CLI resolve the same default model; the gateway URL itself is derived from the login
 * and never written to disk.
 *
 * @author stephen
 */
public class MediaSettingsApi {
    private static final Logger LOGGER = LoggerFactory.getLogger(MediaSettingsApi.class);

    private final PropertiesFileSource props;
    private final Path workspace;
    private final MediaProvider imageMediaProvider;
    private final MediaProvider videoMediaProvider;
    private final MediaModelCatalog catalog;
    private final Path globalConfig;

    public MediaSettingsApi(PropertiesFileSource props, Path workspace, MediaProvider imageMediaProvider,
                            MediaProvider videoMediaProvider, MediaModelCatalog catalog, Path globalConfig) {
        this.props = props;
        this.workspace = workspace;
        this.imageMediaProvider = imageMediaProvider;
        this.videoMediaProvider = videoMediaProvider;
        this.catalog = catalog;
        this.globalConfig = globalConfig;
    }

    public ObjectNode get() {
        if (catalog != null) catalog.ensureLoaded(1_500);
        var global = load(globalConfig);
        var workspaceProps = loadWorkspaceProperties();
        var node = Params.object();
        node.put("signedIn", signedIn());
        putIfPresent(node, "gateway", CliAppHelper.mediaGatewayBase(props));
        putIfPresent(node, "imageBase", CliAppHelper.imageMediaBase(props));
        putIfPresent(node, "videoBase", CliAppHelper.videoMediaBase(props));
        var imageSource = source(imageMediaProvider);
        var videoSource = source(videoMediaProvider);
        node.put("imageSource", imageSource);
        node.put("videoSource", videoSource);
        node.put("imageModel", configured(global, "media.image.model"));
        node.put("videoModel", configured(global, "media.video.model"));
        node.put("effectiveImageModel", effectiveModel(global, workspaceProps, "media.image.model", imageSource,
                catalog == null ? null : catalog.defaultImageModel()));
        node.put("effectiveVideoModel", effectiveModel(global, workspaceProps, "media.video.model", videoSource,
                catalog == null ? null : catalog.defaultVideoModel()));
        models(node.putArray("imageModels"), imageModels(imageSource));
        models(node.putArray("imageEditModels"), imageEditModels(imageSource));
        models(node.putArray("videoModels"), videoModels(videoSource));
        putIfPresent(node, "error", catalog == null ? null : catalog.error());
        node.put("catalogUnavailable", catalog != null && catalog.unavailable());
        node.put("imageModelFromWorkspace", workspaceProps.containsKey("media.image.model"));
        node.put("videoModelFromWorkspace", workspaceProps.containsKey("media.video.model"));
        return node;
    }

    public ObjectNode set(ObjectNode params) {
        if (catalog != null) catalog.ensureLoaded(1_500);
        var properties = load(globalConfig);
        if (params.has("imageModel")) {
            var value = Params.optionalText(params, "imageModel");
            validate("image", value);
            putOrRemove(properties, "media.image.model", value);
        }
        if (params.has("videoModel")) {
            var value = Params.optionalText(params, "videoModel");
            validate("video", value);
            putOrRemove(properties, "media.video.model", value);
        }
        store(globalConfig, properties);

        var node = Params.object();
        node.put("imageModel", properties.getProperty("media.image.model"));
        node.put("videoModel", properties.getProperty("media.video.model"));
        node.put("restartRequired", true);
        return node;
    }

    private void validate(String modality, String model) {
        if (model == null || model.isBlank()) return;
        var provider = "image".equals(modality) ? imageMediaProvider : videoMediaProvider;
        if (!(provider instanceof CoreAiGatewayMediaProvider)) return;
        if (catalog == null || !catalog.hasSnapshot() || catalog.error() != null) return;
        var models = "image".equals(modality) ? catalog.imageModels() : catalog.videoModels();
        // an empty catalog usually means an older/incomplete server; the explicit model is the escape
        // hatch (the tool registration rule follows the same precedence), so do not reject it here
        if (models.isEmpty()) return;
        if (models.stream().noneMatch(hint -> model.equals(hint.modelId()))) {
            throw RpcException.business("UNKNOWN_MODEL",
                    "model is not enabled on this server: " + model, "model", model);
        }
    }

    private String source(MediaProvider provider) {
        if (provider == null) return "none";
        return provider instanceof CoreAiGatewayMediaProvider ? "server" : "custom";
    }

    private List<MediaModelHint> imageModels(String source) {
        return catalog != null && "server".equals(source) ? catalog.imageModels() : List.of();
    }

    private List<MediaModelHint> imageEditModels(String source) {
        return catalog != null && "server".equals(source) ? catalog.imageEditModels() : List.of();
    }

    private List<MediaModelHint> videoModels(String source) {
        return catalog != null && "server".equals(source) ? catalog.videoModels() : List.of();
    }

    private String effectiveModel(Properties global, Properties workspaceProps, String property,
                                  String source, String catalogDefault) {
        var workspaceValue = configured(workspaceProps, property);
        if (workspaceValue != null) return workspaceValue;
        var configured = configured(global, property);
        if (configured != null) return configured;
        return "server".equals(source) ? catalogDefault : null;
    }

    private String configured(Properties properties, String property) {
        return properties == null ? null
                : Optional.ofNullable(properties.getProperty(property)).map(String::trim)
                        .filter(value -> !value.isEmpty()).orElse(null);
    }

    private void models(ArrayNode array, List<MediaModelHint> hints) {
        for (var hint : hints) {
            var item = array.addObject();
            item.put("modelId", hint.modelId());
            if (hint.displayName() != null) item.put("displayName", hint.displayName());
            if (hint.providerName() != null) item.put("providerName", hint.providerName());
        }
    }

    private boolean signedIn() {
        var auth = AuthConfig.load();
        return auth != null && auth.apiKey() != null;
    }

    private Properties loadWorkspaceProperties() {
        var file = workspace.resolve(".core-ai").resolve("agent.properties");
        if (!Files.exists(file)) return new Properties();
        return load(file);
    }

    private void putIfPresent(ObjectNode node, String name, String value) {
        if (value != null && !value.isBlank()) node.put(name, value);
    }

    private void putOrRemove(Properties props, String key, String value) {
        if (value == null || value.isBlank()) props.remove(key);
        else props.setProperty(key, value.trim());
    }

    private Properties load(Path file) {
        var props = new Properties();
        if (!Files.exists(file)) return props;
        try (var in = Files.newInputStream(file)) {
            props.load(in);
        } catch (IOException e) {
            throw RpcException.business("CONFIG_READ_FAILED", "failed to read " + file + ": " + e.getMessage(), e);
        }
        return props;
    }

    private void store(Path file, Properties props) {
        var target = file.toAbsolutePath();
        var parent = target.getParent();
        var fileName = target.getFileName();
        if (parent == null || fileName == null) {
            throw RpcException.business("CONFIG_WRITE_FAILED", "invalid config path: " + target);
        }
        Path temp = null;
        try {
            Files.createDirectories(parent);
            var buffer = new ByteArrayOutputStream();
            props.store(buffer, null);
            // write-then-move: a reader (or a crash) never observes a half-written properties file
            temp = writeTemp(parent, fileName.toString(), buffer.toByteArray());
            moveIntoPlace(temp, target);
        } catch (IOException e) {
            throw RpcException.business("CONFIG_WRITE_FAILED", "failed to write " + file + ": " + e.getMessage(), e);
        } finally {
            deleteTemp(temp);
        }
    }

    private Path writeTemp(Path directory, String name, byte[] content) throws IOException {
        var temp = Files.createTempFile(directory, name, ".tmp");
        try {
            Files.write(temp, content);
            return temp;
        } catch (IOException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
    }

    private void moveIntoPlace(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void deleteTemp(Path temp) {
        if (temp == null) return;
        try {
            Files.deleteIfExists(temp);
        } catch (IOException e) {
            LOGGER.debug("failed to remove temporary properties file: {}", temp, e);
        }
    }
}
