package ai.core.server.skill;

import ai.core.server.domain.SkillResource;
import core.framework.web.exception.BadRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static ai.core.server.skill.SkillResourceLimits.MAX_INLINE_DOC_BYTES;
import static ai.core.server.skill.SkillResourceLimits.MAX_INLINE_RESOURCE_BYTES;
import static ai.core.server.skill.SkillResourceLimits.MAX_RESOURCE_BYTES;

/**
 * Applies the skill resource storage policy: UTF-8 text at or below the inline threshold stays in
 * the document, everything else (binary, oversized, over-budget) moves to object storage. Fails
 * loudly instead of silently mangling bytes when object storage is required but missing.
 *
 * @author stephen
 */
class SkillResourceWriter {
    private static final Logger LOGGER = LoggerFactory.getLogger(SkillResourceWriter.class);
    private static final int MAX_LISTED_FILES = 10;

    /** True when the new rows are byte-identical to the stored ones, so the stored digest still holds. */
    static boolean unchanged(List<SkillResource> previous, List<SkillResource> merged) {
        if (previous == null || previous.isEmpty()) return merged == null || merged.isEmpty();
        if (merged == null || previous.size() != merged.size()) return false;
        for (var resource : merged) {
            var stored = findByPath(previous, resource.path);
            if (stored == null || !sameRow(stored, resource)) return false;
        }
        return true;
    }

    private static SkillResource findByPath(List<SkillResource> resources, String path) {
        return resources.stream().filter(resource -> resource.path.equals(path)).findFirst().orElse(null);
    }

    /** Blob rows compare by their content-addressed storage path, which pins the bytes. */
    private static boolean sameRow(SkillResource stored, SkillResource resource) {
        if (stored.content != null || resource.content != null) {
            return stored.content != null && stored.content.equals(resource.content);
        }
        return stored.storagePath != null && stored.storagePath.equals(resource.storagePath);
    }

    private static SkillResource inline(String path, byte[] bytes, String text) {
        var resource = new SkillResource();
        resource.path = path;
        resource.content = text;
        resource.size = (long) bytes.length;
        resource.sha256 = SkillBlobStore.sha256(bytes);
        return resource;
    }

    private static void checkSize(String path, byte[] bytes) {
        if (bytes.length > MAX_RESOURCE_BYTES) {
            throw new BadRequestException("resource is too large (max " + MAX_RESOURCE_BYTES + " bytes): " + path);
        }
    }

    private static BadRequestException binaryRequiresStorage(List<String> paths) {
        var listed = paths.size() <= MAX_LISTED_FILES ? paths : paths.subList(0, MAX_LISTED_FILES);
        var suffix = paths.size() <= MAX_LISTED_FILES ? "" : " (+" + (paths.size() - MAX_LISTED_FILES) + " more)";
        return new BadRequestException("binary resources require object storage: " + String.join(", ", listed) + suffix);
    }

    private static String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    private static long utf8Length(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static long inlineLength(SkillResource resource) {
        if (resource.content == null) return 0;
        return resource.size != null ? resource.size : utf8Length(resource.content);
    }

    private final SkillBlobStore blobStore;

    SkillResourceWriter(SkillBlobStore blobStore) {
        this.blobStore = blobStore;
    }

    /** Classifies and stores a full resource set; null when the skill has no resources. */
    List<SkillResource> toResources(String skillId, String skillMd, Map<String, byte[]> files) {
        if (files == null || files.isEmpty()) return null;
        var candidates = new ArrayList<Candidate>(files.size());
        var binaries = new ArrayList<String>();
        for (var entry : files.entrySet()) {
            checkSize(entry.getKey(), entry.getValue());
            var text = decodeUtf8(entry.getValue());
            if (text == null) binaries.add(entry.getKey());
            candidates.add(new Candidate(entry.getKey(), entry.getValue(), text));
        }
        if (!binaries.isEmpty() && !blobStore.configured()) throw binaryRequiresStorage(binaries);

        var resources = new ArrayList<SkillResource>(candidates.size());
        for (var candidate : candidates) {
            boolean external = candidate.text() == null
                    || candidate.bytes().length > MAX_INLINE_RESOURCE_BYTES && blobStore.configured();
            if (external) {
                resources.add(blob(skillId, candidate.path(), candidate.bytes()));
            } else {
                if (candidate.bytes().length > MAX_INLINE_RESOURCE_BYTES) {
                    LOGGER.warn("resource {} exceeds the inline threshold and object storage is not configured; keeping it inline", candidate.path());
                }
                resources.add(inline(candidate.path(), candidate.bytes(), candidate.text()));
            }
        }
        enforceBudget(skillId, skillMd, resources);
        return resources;
    }

    /** Merges the update rows over the stored resources; keep rows must exist and stay unchanged. */
    List<SkillResource> merge(String skillId, List<SkillResource> existing, List<SkillResourceUpdate> updates) {
        Map<String, SkillResource> byPath = new HashMap<>();
        if (existing != null) {
            for (var resource : existing) byPath.put(resource.path, resource);
        }
        var seen = new HashSet<String>();
        var merged = new ArrayList<SkillResource>(updates.size());
        for (var update : updates) {
            if (!seen.add(update.path())) throw new BadRequestException("duplicate resource path: " + update.path());
            if (update.keep()) {
                var kept = byPath.get(update.path());
                if (kept == null) throw new BadRequestException("resource does not exist and cannot be kept: " + update.path());
                merged.add(kept);
            } else if (update.content() == null) {
                throw new BadRequestException("resource content is required: " + update.path());
            } else {
                merged.add(newResource(skillId, update.path(), update.content().getBytes(StandardCharsets.UTF_8)));
            }
        }
        return merged;
    }

    /** Moves inline resources to object storage when the inline part exceeds the document budget. */
    void enforceBudget(String skillId, String skillMd, List<SkillResource> resources) {
        if (resources == null || resources.isEmpty()) return;
        long inline = utf8Length(skillMd);
        for (var resource : resources) inline += inlineLength(resource);
        if (inline <= MAX_INLINE_DOC_BYTES) return;
        if (!blobStore.configured()) {
            throw new BadRequestException("skill is too large to store inline (" + inline + " bytes, budget " + MAX_INLINE_DOC_BYTES
                    + "); configure object storage or reduce resource sizes");
        }
        var candidates = new ArrayList<SkillResource>();
        for (var resource : resources) {
            if (resource.content != null) candidates.add(resource);
        }
        candidates.sort(Comparator.comparingLong(SkillResourceWriter::inlineLength).reversed().thenComparing(resource -> resource.path));
        for (var candidate : candidates) {
            if (inline <= MAX_INLINE_DOC_BYTES) break;
            inline -= inlineLength(candidate);
            moveToBlob(skillId, candidate);
        }
    }

    private SkillResource newResource(String skillId, String path, byte[] bytes) {
        checkSize(path, bytes);
        if (bytes.length > MAX_INLINE_RESOURCE_BYTES && blobStore.configured()) return blob(skillId, path, bytes);
        if (bytes.length > MAX_INLINE_RESOURCE_BYTES) {
            LOGGER.warn("resource {} exceeds the inline threshold and object storage is not configured; keeping it inline", path);
        }
        return inline(path, bytes, new String(bytes, StandardCharsets.UTF_8));
    }

    private SkillResource blob(String skillId, String path, byte[] bytes) {
        var resource = new SkillResource();
        resource.path = path;
        moveToBlob(skillId, resource, bytes);
        return resource;
    }

    private void moveToBlob(String skillId, SkillResource resource) {
        moveToBlob(skillId, resource, resource.content.getBytes(StandardCharsets.UTF_8));
    }

    private void moveToBlob(String skillId, SkillResource resource, byte[] bytes) {
        resource.storagePath = blobStore.put(skillId, resource.path, bytes);
        resource.size = (long) bytes.length;
        resource.sha256 = SkillBlobStore.sha256(bytes);
        resource.contentType = SkillBlobStore.contentTypeOf(resource.path);
        resource.content = null;
    }

    private record Candidate(String path, byte[] bytes, String text) {
    }
}
