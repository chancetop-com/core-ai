package ai.core.cli.config;

import ai.core.cli.utils.PathUtils;
import ai.core.llm.LLMProviderType;
import ai.core.utils.AtomicFiles;
import ai.core.utils.JsonUtil;
import core.framework.api.json.Property;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * User-level store for direct OpenAI-compatible providers ({@code ~/.core-ai/llm-providers.json}).
 * Credentials live here instead of {@code agent.properties}: signing in to a core-ai-server or switching
 * environments never touches this file, and the desktop's Settings surface can edit it without a running
 * workspace/engine.
 *
 * @author stephen
 */
public final class LlmProviderStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(LlmProviderStore.class);
    private static final int VERSION = 1;
    private static final Set<LLMProviderType> ALLOWED_TYPES = Set.of(
            LLMProviderType.DEEPSEEK, LLMProviderType.OPENAI, LLMProviderType.OPENROUTER);

    public static LlmProviderStore load() {
        return load(PathUtils.LLM_PROVIDERS);
    }

    public static LlmProviderStore load(Path file) {
        return new LlmProviderStore(file, read(file));
    }

    public static FileModel read(Path file) {
        if (!Files.exists(file)) return blank();
        try {
            var parsed = JsonUtil.fromJson(FileModel.class, Files.readString(file));
            if (parsed == null) return blank();
            normalize(parsed);
            return parsed;
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("failed to read llm provider store {}, ignoring it: {}", file, e.getMessage());
            return blank();
        }
    }

    private static FileModel blank() {
        var model = new FileModel();
        model.profiles = new ArrayList<>();
        return model;
    }

    private static void normalize(FileModel model) {
        if (model.profiles == null) model.profiles = new ArrayList<>();
        model.profiles.removeIf(profile -> profile == null || profile.type == null || profile.type.isBlank());
        for (var profile : model.profiles) {
            if (profile.models == null) profile.models = new ArrayList<>();
            if (profile.reasoningLevels == null) profile.reasoningLevels = new ArrayList<>();
            if (profile.reasoningEffortMap == null) profile.reasoningEffortMap = new java.util.LinkedHashMap<>();
            if (profile.id == null || profile.id.isBlank()) profile.id = profile.type.toLowerCase(Locale.ROOT);
        }
    }

    private static void restrictPermissions(Path file) {
        try {
            Files.setPosixFilePermissions(file, Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (IOException | UnsupportedOperationException ignored) {
            // Windows / file systems without POSIX permissions: rely on the user profile ACL.
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private final Path file;
    private FileModel model;

    private LlmProviderStore(Path file, FileModel model) {
        this.file = file;
        this.model = model;
    }

    public Path file() {
        return file;
    }

    public List<LlmProviderProfile> profiles() {
        return List.copyOf(model.profiles);
    }

    public String defaultConnection() {
        return model.defaultConnection;
    }

    public String serverModel() {
        return model.serverModel;
    }

    public Optional<LlmProviderProfile> profile(LLMProviderType type) {
        return model.profiles.stream()
                .filter(profile -> type.getName().equalsIgnoreCase(profile.type))
                .findFirst();
    }

    public Optional<LlmProviderProfile> profile(String id) {
        return model.profiles.stream()
                .filter(profile -> profile.id.equalsIgnoreCase(id) || profile.type.equalsIgnoreCase(id))
                .findFirst();
    }

    public void saveProfile(LlmProviderProfile profile) {
        validate(profile);
        var fresh = read(file);
        var existing = fresh.profiles.stream()
                .filter(item -> item.type.equalsIgnoreCase(profile.type))
                .findFirst();
        if (existing.isPresent()) {
            fresh.profiles.set(fresh.profiles.indexOf(existing.get()), profile);
        } else {
            fresh.profiles.add(profile);
        }
        write(fresh);
        this.model = fresh;
    }

    public void removeProfile(String id) {
        var fresh = read(file);
        fresh.profiles.removeIf(profile -> profile.id.equalsIgnoreCase(id) || profile.type.equalsIgnoreCase(id));
        if (fresh.defaultConnection != null
                && fresh.defaultConnection.equalsIgnoreCase(id)) {
            fresh.defaultConnection = null;
        }
        write(fresh);
        this.model = fresh;
    }

    public void setDefaultConnection(String connection) {
        var fresh = read(file);
        fresh.defaultConnection = blankToNull(connection);
        write(fresh);
        this.model = fresh;
    }

    public void setServerModel(String serverModel) {
        var fresh = read(file);
        fresh.serverModel = blankToNull(serverModel);
        write(fresh);
        this.model = fresh;
    }

    public void setDefaultModel(LLMProviderType type, String model) {
        var fresh = read(file);
        for (var profile : fresh.profiles) {
            if (type.getName().equalsIgnoreCase(profile.type)) {
                profile.defaultModel = blankToNull(model);
                break;
            }
        }
        write(fresh);
        this.model = fresh;
    }

    private void validate(LlmProviderProfile profile) {
        if (profile == null || profile.type == null || profile.type.isBlank()) {
            throw new IllegalArgumentException("provider type is required");
        }
        LLMProviderType type;
        try {
            type = LLMProviderType.fromName(profile.type);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unsupported provider type: " + profile.type, e);
        }
        if (!ALLOWED_TYPES.contains(type)) {
            throw new IllegalArgumentException("unsupported provider type: " + profile.type);
        }
        if (profile.id == null || profile.id.isBlank()) profile.id = type.getName();
        if (!profile.id.equalsIgnoreCase(type.getName())) {
            throw new IllegalArgumentException("profile id must match its provider type in v1: " + profile.id);
        }
        profile.id = type.getName();
        profile.type = type.getName();
        if (profile.baseUrl == null || profile.baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl is required");
        }
        var base = profile.baseUrl.trim().toLowerCase(Locale.ROOT);
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            throw new IllegalArgumentException("baseUrl must start with http:// or https://");
        }
        if (profile.models == null) profile.models = new ArrayList<>();
        if (profile.reasoningLevels == null) profile.reasoningLevels = new ArrayList<>();
        if (profile.reasoningEffortMap == null) profile.reasoningEffortMap = new java.util.LinkedHashMap<>();
    }

    private void write(FileModel fresh) {
        fresh.version = VERSION;
        try {
            var parent = file.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            AtomicFiles.writeString(file, JsonUtil.toJson(fresh));
            restrictPermissions(file);
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("failed to write " + file + ": " + e.getMessage(), e);
        }
    }

    /** Defensive copy for callers that need to build a profile from a map, e.g. tests. */
    public Optional<Map<String, String>> reasoningEffortMap(String type) {
        return profile(type).map(profile -> profile.reasoningEffortMap);
    }

    public static final class FileModel {
        public Integer version = VERSION;
        @Property(name = "default")
        public String defaultConnection;
        public String serverModel;
        public List<LlmProviderProfile> profiles = new ArrayList<>();
    }
}
