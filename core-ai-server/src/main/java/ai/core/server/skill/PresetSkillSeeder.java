package ai.core.server.skill;

import ai.core.server.domain.SkillDefinition;
import ai.core.server.domain.SkillSourceType;
import ai.core.server.domain.SkillTier;
import core.framework.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Idempotent PostgreSQL startup seeder for the seven shipped builtin preset skills. Runs only when
 * sys.store.mode=pg (Mongo deployments continue using SchemaMigrationVPresetSkills). Updates
 * untouched presets via revision CAS when the shipped content digest changes, while preserving
 * admin-edited copies.
 *
 * @author stephen
 */
public class PresetSkillSeeder {
    private static final Logger LOGGER = LoggerFactory.getLogger(PresetSkillSeeder.class);
    public static final String SEED_DIGEST_KEY = "preset_seed_digest";
    public static final String PRESET_VERSION = "1.0.0";
    public static final String PRESET_AUTHOR = "core-ai";
    public static final List<String> PRESET_NAMES = List.of(
        "ai-character-sheet",
        "ai-scene-sheet",
        "ai-contact-sheet",
        "ai-camera-language",
        "ai-shot-language",
        "ai-lighting-looks",
        "ai-cover-poster"
    );

    public static String skillId(String name) {
        return "skill-" + name;
    }

    @Inject
    SkillStore skillStore;

    public void seed() {
        int inserted = 0;
        int updated = 0;
        int skipped = 0;
        for (var name : PRESET_NAMES) {
            var content = readResource("preset-skills/" + name + ".md");
            var outcome = seedOne(name, content);
            if (outcome == SeedOutcome.INSERTED) inserted++;
            else if (outcome == SeedOutcome.UPDATED) updated++;
            else skipped++;
        }
        LOGGER.info("preset skills seeded (pg), total={}, inserted={}, updated={}, unchanged={}",
            PRESET_NAMES.size(), inserted, updated, skipped);
    }

    SeedOutcome seedOne(String name, String content) {
        var id = skillId(name);
        var digest = SkillDigest.of(content, null);
        var description = frontmatterDescription(content);
        var metadata = normalizedMetadata(name, digest);

        var existing = skillStore.get(id).orElse(null);
        if (existing == null) {
            var entity = newPresetEntity(id, name, content, description, metadata, digest);
            return skillStore.insertIfAbsent(entity) ? SeedOutcome.INSERTED : SeedOutcome.UNCHANGED;
        }
        if (isAlreadyConverged(existing, digest)) {
            return SeedOutcome.UNCHANGED;
        }
        if (wasManuallyModified(existing)) {
            LOGGER.info("preset skill was modified by admin, skipping seed overwrite, id={}", id);
            return SeedOutcome.UNCHANGED;
        }
        int expectedRevision = existing.revision == null ? 0 : existing.revision;
        var updated = updatedPresetEntity(existing, name, content, description, metadata, digest);
        updated.revision = expectedRevision + 1;
        return skillStore.replaceIfRevision(updated, expectedRevision) ? SeedOutcome.UPDATED : SeedOutcome.UNCHANGED;
    }

    private SkillDefinition newPresetEntity(String id, String name, String content, String description,
                                            Map<String, String> metadata, String digest) {
        var now = ZonedDateTime.now();
        var entity = new SkillDefinition();
        entity.id = id;
        entity.namespace = "builtin";
        entity.name = name;
        entity.qualifiedName = "builtin/" + name;
        entity.directoryName = name;
        entity.description = description;
        entity.sourceType = SkillSourceType.UPLOAD;
        entity.tier = SkillTier.CORE;
        entity.content = content;
        entity.metadata = metadata;
        entity.version = PRESET_VERSION;
        entity.digest = digest;
        entity.revision = 1;
        entity.userId = "system";
        entity.createdAt = now;
        entity.updatedAt = now;
        return entity;
    }

    private SkillDefinition updatedPresetEntity(SkillDefinition existing, String name, String content,
                                                String description, Map<String, String> metadata, String digest) {
        var updated = newPresetEntity(existing.id, name, content, description, metadata, digest);
        updated.createdAt = existing.createdAt;
        updated.stats = existing.stats;
        updated.qualityReceipt = existing.qualityReceipt;
        return updated;
    }

    private boolean isAlreadyConverged(SkillDefinition existing, String digest) {
        if (existing.tier != SkillTier.CORE) return false;
        if (!digest.equals(existing.digest)) return false;
        if (existing.metadata == null) return false;
        return PRESET_AUTHOR.equals(existing.metadata.get("author"))
            && PRESET_VERSION.equals(existing.metadata.get("version"))
            && existing.metadata.get("tags") != null
            && digest.equals(existing.metadata.get(SEED_DIGEST_KEY));
    }

    private boolean wasManuallyModified(SkillDefinition existing) {
        if (!"system".equals(existing.userId)) return true;
        if (existing.metadata == null) return false;
        var seedDigest = existing.metadata.get(SEED_DIGEST_KEY);
        return seedDigest != null && !seedDigest.equals(existing.digest);
    }

    private Map<String, String> normalizedMetadata(String name, String digest) {
        var metadata = new LinkedHashMap<String, String>();
        metadata.put("version", PRESET_VERSION);
        metadata.put("author", PRESET_AUTHOR);
        metadata.put("tags", "builtin,preset," + name);
        metadata.put(SEED_DIGEST_KEY, digest);
        return Map.copyOf(metadata);
    }

    private String frontmatterDescription(String content) {
        for (var line : content.split("\n")) {
            var trimmed = line.trim();
            if (trimmed.startsWith("description:")) {
                return trimmed.substring("description:".length()).trim();
            }
        }
        return "";
    }

    private String readResource(String path) {
        try (var stream = Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
            if (stream == null) throw new IllegalStateException("missing classpath resource: " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read preset skill resource: " + path, e);
        }
    }

    enum SeedOutcome {
        INSERTED, UPDATED, UNCHANGED
    }
}
