package ai.core.server.skill.quality;

import ai.core.server.domain.SkillDefinition;
import ai.core.server.domain.SkillSourceType;
import ai.core.server.domain.SkillTier;
import ai.core.server.skill.SkillDigest;
import ai.core.server.skill.SkillTiers;
import ai.core.skill.SkillLoader;
import core.framework.crypto.Hash;
import core.framework.web.exception.BadRequestException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Builds the immutable candidate from the raw upload. Path normalization happens before the ignored-resource
 * filter so a link written as {@code ./references/x.md} still resolves to the packaged file.
 *
 * @author core-ai
 */
public final class SkillPublishCandidateBuilder {
    static String normalizePath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) throw invalid("resource path is empty");
        var path = rawPath.replace('\\', '/').trim();
        while (path.startsWith("./")) path = path.substring(2);
        path = path.replaceAll("/{2,}", "/");
        if (path.startsWith("/") || path.matches("^[A-Za-z]:/.*")) {
            throw invalid("resource path must be relative: " + rawPath);
        }
        for (var segment : path.split("/")) {
            if ("..".equals(segment)) throw invalid("resource path must not escape the skill directory: " + rawPath);
        }
        return path;
    }

    private static String metadataVersion(Map<String, String> metadata) {
        return metadata == null ? null : metadata.get("version");
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) return first;
        return second;
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static BadRequestException invalid(String message) {
        return new BadRequestException(message, "SKILL_DOCUMENT_INVALID");
    }

    static String lowercase(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    private final SkillDocumentParser parser = new SkillDocumentParser();

    private String skillId;
    private String namespace;
    private String directoryName;
    private String content;
    private Map<String, byte[]> resources = Map.of();
    private String name;
    private String description;
    private Map<String, String> metadata = Map.of();
    private List<String> allowedTools = List.of();
    private String version;
    private SkillDocument document;
    private String packageDigest;
    private String metadataHash;
    private int revision;
    private SkillSourceType sourceType;
    private SkillTier targetTier;
    private String previousVersion;
    private boolean contentChanged;

    public SkillPublishCandidateBuilder withTargetTier(SkillTier targetTier) {
        this.targetTier = targetTier;
        return this;
    }

    public SkillPublishCandidate build(String namespace, String directoryName, String content,
                                       Map<String, byte[]> rawResources, SkillDefinition existing) {
        return build(namespace, directoryName, content, rawResources, null, existing);
    }

    /**
     * {@code sourceType} is the write intent (a repo import is third-party content); when null it falls back to the
     * existing record, and a new write is an upload otherwise, so the candidate and the stored entity agree.
     */
    public SkillPublishCandidate build(String namespace, String directoryName, String content,
                                       Map<String, byte[]> rawResources, SkillSourceType sourceType, SkillDefinition existing) {
        this.skillId = existing == null ? null : existing.id;
        this.namespace = namespace;
        this.directoryName = directoryName;
        this.content = content;
        this.sourceType = sourceType != null ? sourceType
            : existing == null ? SkillSourceType.UPLOAD : existing.sourceType;
        this.targetTier = this.targetTier != null ? this.targetTier : SkillTiers.defaultFor(this.sourceType, existing);
        this.document = parser.parse(content);
        this.resources = normalizeResources(rawResources);
        this.name = string(document.frontMatter.get("name"));
        this.description = string(document.frontMatter.get("description"));
        if (name == null || name.isBlank()) {
            throw invalid("SKILL.md frontmatter must contain a non-empty name");
        }
        if (description == null || description.isBlank()) {
            throw invalid("SKILL.md frontmatter must contain a non-empty description");
        }
        this.metadata = parseMetadata(document.frontMatter.get("metadata"));
        this.allowedTools = parseAllowedTools(document.frontMatter.get("allowed-tools"));
        this.version = firstNonBlank(metadata.get("version"), string(document.frontMatter.get("version")));
        this.packageDigest = SkillDigest.of(content, resources);
        this.metadataHash = computeMetadataHash();
        this.previousVersion = existing == null ? null : metadataVersion(existing.metadata);
        this.contentChanged = existing == null || !packageDigest.equals(existing.digest);
        this.revision = existing == null || existing.revision == null ? 1 : existing.revision + 1;
        return new SkillPublishCandidate(this);
    }

    private Map<String, byte[]> normalizeResources(Map<String, byte[]> raw) {
        if (raw == null || raw.isEmpty()) return Map.of();
        var normalized = new TreeMap<String, byte[]>(String.CASE_INSENSITIVE_ORDER);
        for (var entry : raw.entrySet()) {
            var path = normalizePath(entry.getKey());
            if (SkillLoader.isIgnoredResource(path)) continue;
            if (normalized.put(path, entry.getValue()) != null) {
                throw invalid("duplicate resource path after normalization: " + path);
            }
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(normalized));
    }

    private Map<String, String> parseMetadata(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) return Map.of();
        var result = new LinkedHashMap<String, String>();
        map.forEach((key, value) -> result.put(String.valueOf(key), scalar(value)));
        return Collections.unmodifiableMap(result);
    }

    private List<String> parseAllowedTools(Object raw) {
        if (raw instanceof String text) {
            var trimmed = text.trim();
            return trimmed.isEmpty() ? List.of() : List.of(trimmed.split("\\s+"));
        }
        if (raw instanceof List<?> list) {
            var result = new ArrayList<String>();
            for (var item : list) {
                var value = string(item);
                if (value != null && !value.isBlank()) result.add(value.trim());
            }
            return List.copyOf(result);
        }
        return List.of();
    }

    private String scalar(Object value) {
        if (value == null) return "";
        if (value instanceof List<?> list) {
            var parts = new ArrayList<String>();
            for (var item : list) parts.add(string(item));
            return String.join(",", parts);
        }
        return String.valueOf(value);
    }

    private String computeMetadataHash() {
        var canonical = new StringBuilder(256)
            .append("name=").append(name)
            .append("\ndescription=").append(description)
            .append("\nmetadata=").append(new TreeMap<>(metadata))
            .append("\nallowedTools=").append(allowedTools)
            .append('\n');
        return "sha256:" + Hash.sha256Hex(canonical.toString());
    }

    String skillId() {
        return skillId;
    }

    String namespace() {
        return namespace;
    }

    String directoryName() {
        return directoryName;
    }

    String content() {
        return content;
    }

    Map<String, byte[]> resources() {
        return resources;
    }

    String name() {
        return name;
    }

    String description() {
        return description;
    }

    Map<String, String> metadata() {
        return metadata;
    }

    List<String> allowedTools() {
        return allowedTools;
    }

    String version() {
        return version;
    }

    SkillDocument document() {
        return document;
    }

    String packageDigest() {
        return packageDigest;
    }

    String metadataHash() {
        return metadataHash;
    }

    int revision() {
        return revision;
    }

    SkillSourceType sourceType() {
        return sourceType;
    }

    SkillTier targetTier() {
        return targetTier;
    }

    String previousVersion() {
        return previousVersion;
    }

    boolean contentChanged() {
        return contentChanged;
    }
}
