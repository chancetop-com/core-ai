package ai.core.server.skill.quality;

import ai.core.server.domain.SkillSourceType;

import java.util.List;
import java.util.Map;

/**
 * The exact package a publish would commit. It is built once, every rule reads it, and the write path
 * projects the entity fields from the same object; a second parse or a re-read of resources is not allowed.
 *
 * @author core-ai
 */
public final class SkillPublishCandidate {
    public final String skillId;
    public final String namespace;
    public final String directoryName;
    public final String content;
    public final Map<String, byte[]> resources;
    public final String name;
    public final String description;
    public final Map<String, String> metadata;
    public final List<String> allowedTools;
    public final String version;
    public final SkillDocument document;
    public final String packageDigest;
    public final String metadataHash;
    public final int revision;
    public final SkillSourceType sourceType;
    public final String previousVersion;
    public final boolean contentChanged;

    public SkillPublishCandidate(SkillPublishCandidateBuilder builder) {
        this.skillId = builder.skillId();
        this.namespace = builder.namespace();
        this.directoryName = builder.directoryName();
        this.content = builder.content();
        this.resources = builder.resources();
        this.name = builder.name();
        this.description = builder.description();
        this.metadata = builder.metadata();
        this.allowedTools = builder.allowedTools();
        this.version = builder.version();
        this.document = builder.document();
        this.packageDigest = builder.packageDigest();
        this.metadataHash = builder.metadataHash();
        this.revision = builder.revision();
        this.sourceType = builder.sourceType();
        this.previousVersion = builder.previousVersion();
        this.contentChanged = builder.contentChanged();
    }

    public boolean isNew() {
        return skillId == null;
    }

    public byte[] resource(String path) {
        return resources.get(path);
    }

    public String qualifiedName() {
        return namespace + "/" + name;
    }

    public boolean isRepoSource() {
        return sourceType == SkillSourceType.REPO;
    }
}
