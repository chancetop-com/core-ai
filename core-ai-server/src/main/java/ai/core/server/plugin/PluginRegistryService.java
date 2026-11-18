package ai.core.server.plugin;

import ai.core.api.server.plugin.PluginRegistryEntryView;
import ai.core.api.server.plugin.PluginRegistrySearchResponse;
import ai.core.api.server.plugin.RegisterPluginRequest;
import ai.core.server.domain.PluginRegistryEntry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import core.framework.inject.Inject;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.NotFoundException;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Registry rules: identity/version/capabilities come from the manifest, the package is pinned by a
 * digest, and only HTTPS (or local HTTP for development) download URLs are accepted. The registry
 * never hosts or rewrites package bytes; it tells a desktop what to download and how to verify it.
 *
 * @author stephen
 */
public class PluginRegistryService {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern PLUGIN_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    private static final Pattern DIGEST = Pattern.compile("[a-fA-F0-9]{64}");
    private static final Set<String> CAPABILITIES = Set.of("ui.dock", "ui.settings", "ui.composer", "engine.skills");
    private static final int MAX_MANIFEST_BYTES = 64 * 1024;
    private static final long MAX_PACKAGE_BYTES = 256L * 1024 * 1024;

    @Inject
    PluginRegistryStore store;

    public PluginRegistrySearchResponse search(String query, Integer offset, Integer limit) {
        int effectiveLimit = Math.clamp(limit != null ? limit : 50, 1, 100);
        int effectiveOffset = Math.max(0, offset != null ? offset : 0);
        var response = new PluginRegistrySearchResponse();
        response.plugins = store.search(query, effectiveOffset, effectiveLimit).stream().map(this::toView).toList();
        response.total = store.count(query);
        return response;
    }

    public PluginRegistryEntryView get(String id) {
        return store.get(id).map(this::toView).orElseThrow(() -> new NotFoundException("plugin not found: " + id));
    }

    public PluginRegistryEntryView register(String userId, RegisterPluginRequest request) {
        var valid = validate(request);
        var now = ZonedDateTime.now();
        var entry = store.get(valid.id()).orElseGet(() -> {
            var created = new PluginRegistryEntry();
            created.id = valid.id();
            created.createdAt = now;
            return created;
        });
        entry.name = valid.manifest().path("name").asText(valid.id());
        entry.version = valid.version();
        entry.description = text(valid.manifest().path("description"), 500);
        entry.vendor = text(request.vendor, 200);
        entry.homepage = valid.homepage();
        entry.downloadUrl = valid.downloadUrl();
        entry.sha256 = valid.sha256();
        entry.size = request.size;
        entry.manifest = request.manifest;
        entry.requiredCapabilities = valid.required();
        entry.optionalCapabilities = valid.optional();
        entry.components = valid.components();
        entry.ownerId = userId;
        entry.updatedAt = now;
        store.upsert(entry);
        return toView(entry);
    }

    private Validated validate(RegisterPluginRequest request) {
        var manifest = parseManifest(request.manifest);
        var coreai = manifest.path("coreai");
        var id = coreai.path("id").asText("");
        if (!PLUGIN_ID.matcher(id).matches()) {
            throw new BadRequestException("manifest coreai.id is missing or invalid");
        }
        var version = manifest.path("version").asText("");
        if (version.isBlank() || version.length() > 64) {
            throw new BadRequestException("manifest version is required");
        }
        var downloadUrl = validateUrl(request.downloadUrl, "download_url");
        var homepage = request.homepage == null || request.homepage.isBlank()
            ? null : validateUrl(request.homepage, "homepage");
        var sha256 = request.sha256 == null ? "" : request.sha256.trim().toLowerCase();
        if (!DIGEST.matcher(sha256).matches()) {
            throw new BadRequestException("sha256 must be 64 hexadecimal characters");
        }
        if (request.size != null && (request.size <= 0 || request.size > MAX_PACKAGE_BYTES)) {
            throw new BadRequestException("size must be between 1 and " + MAX_PACKAGE_BYTES);
        }
        var required = stringList(coreai.path("capabilities").path("required"), "required capability");
        var optional = stringList(coreai.path("capabilities").path("optional"), "optional capability");
        required.forEach(this::validateCapability);
        optional.forEach(this::validateCapability);
        var components = components(coreai.path("components"));
        if (components.contains("skills") && !required.contains("engine.skills")) {
            throw new BadRequestException("skills component requires capability engine.skills");
        }
        return new Validated(manifest, id, version, downloadUrl, homepage, sha256, required, optional, components);
    }

    public void delete(String id) {
        if (store.get(id).isEmpty()) throw new NotFoundException("plugin not found: " + id);
        store.delete(id);
    }

    private JsonNode parseManifest(String value) {
        if (value == null || value.isBlank()) throw new BadRequestException("manifest is required");
        if (value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_MANIFEST_BYTES) {
            throw new BadRequestException("manifest is too large");
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(value);
        } catch (Exception e) {
            throw new BadRequestException("manifest is not valid JSON: " + e.getMessage(), "BAD_REQUEST", e);
        }
        if (root == null || !root.isObject()) throw new BadRequestException("manifest must be a JSON object");
        var coreai = root.path("coreai");
        if (!coreai.isObject()) throw new BadRequestException("manifest must contain a coreai block");
        if (coreai.path("schemaVersion").asInt(0) != 1) {
            throw new BadRequestException("unsupported coreai.schemaVersion");
        }
        return root;
    }

    private void validateCapability(String capability) {
        if (!CAPABILITIES.contains(capability)) {
            throw new BadRequestException("unsupported capability: " + capability);
        }
    }

    private List<String> stringList(JsonNode node, String label) {
        if (node == null || node.isMissingNode() || node.isNull()) return List.of();
        if (!node.isArray()) throw new BadRequestException(label + " must be an array");
        var result = new ArrayList<String>();
        for (var item : node) {
            if (!item.isTextual() || item.asText().length() > 100 || result.contains(item.asText())) {
                throw new BadRequestException("invalid " + label);
            }
            result.add(item.asText());
        }
        return result;
    }

    private List<String> components(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return List.of();
        if (!node.isArray()) throw new BadRequestException("components must be an array");
        var result = new ArrayList<String>();
        for (var item : node) {
            var kind = item.path("kind").asText("");
            if (kind.isBlank() || kind.length() > 40) throw new BadRequestException("invalid component kind");
            result.add(kind);
        }
        return result;
    }

    private String validateUrl(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 2048) {
            throw new BadRequestException(field + " is required");
        }
        try {
            var uri = new URI(value);
            var scheme = uri.getScheme();
            if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) {
                throw new BadRequestException(field + " must be an http(s) URL");
            }
            if (uri.getHost() == null) throw new BadRequestException(field + " has no host");
            return uri.toString();
        } catch (URISyntaxException e) {
            throw new BadRequestException(field + " is not a valid URL", "BAD_REQUEST", e);
        }
    }

    private String text(JsonNode node, int max) {
        if (node == null || !node.isTextual()) return null;
        var value = node.asText();
        if (value.isBlank()) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    private String text(String value, int max) {
        if (value == null || value.isBlank()) return null;
        var trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    private PluginRegistryEntryView toView(PluginRegistryEntry entry) {
        var view = new PluginRegistryEntryView();
        view.id = entry.id;
        view.name = entry.name;
        view.version = entry.version;
        view.description = entry.description;
        view.vendor = entry.vendor;
        view.homepage = entry.homepage;
        view.downloadUrl = entry.downloadUrl;
        view.sha256 = entry.sha256;
        view.size = entry.size;
        view.manifest = entry.manifest;
        view.requiredCapabilities = entry.requiredCapabilities;
        view.optionalCapabilities = entry.optionalCapabilities;
        view.components = entry.components;
        view.ownerId = entry.ownerId;
        view.updatedAt = entry.updatedAt;
        return view;
    }

    private record Validated(JsonNode manifest, String id, String version, String downloadUrl, String homepage,
                             String sha256, List<String> required, List<String> optional, List<String> components) {
    }
}
