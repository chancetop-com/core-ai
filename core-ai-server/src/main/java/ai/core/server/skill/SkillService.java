package ai.core.server.skill;

import ai.core.server.domain.SkillDefinition;
import ai.core.server.domain.SkillResource;
import ai.core.server.domain.SkillSourceType;
import ai.core.server.util.IdLists;
import ai.core.skill.SkillLoader;
import ai.core.skill.SkillMetadata;
import com.mongodb.client.model.Filters;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.web.exception.ForbiddenException;
import core.framework.web.exception.NotFoundException;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @author stephen
 */
public class SkillService {
    private static final Logger LOGGER = LoggerFactory.getLogger(SkillService.class);
    private static final int MAX_SKILL_FILE_SIZE = 10 * 1024 * 1024;

    private static ForbiddenException unavailableSkill() {
        return new ForbiddenException("skill is unavailable");
    }

    private static boolean digestStale(String digest, boolean contentChanged, List<SkillResource> previous, List<SkillResource> current) {
        if (digest == null || contentChanged) return true;
        return !SkillResourceWriter.unchanged(previous, current);
    }

    @Inject
    MongoCollection<SkillDefinition> skillCollection;

    @Inject
    SkillBlobStore blobStore;

    private volatile Runnable catalogInvalidator = () -> {
    };

    /** Hook for the skill hub catalog: fires after any write path that changed the catalog. */
    public void setCatalogInvalidator(Runnable invalidator) {
        if (invalidator != null) catalogInvalidator = invalidator;
    }

    private void invalidateCatalog() {
        catalogInvalidator.run();
    }

    public String extractRepoOwner(String repoUrl) {
        return repoManager().extractRepoOwner(repoUrl);
    }

    public SkillDefinition upload(String userId, String namespace, byte[] skillFileBytes, Map<String, byte[]> resources) {
        String content = new String(skillFileBytes, StandardCharsets.UTF_8);
        var loader = new SkillLoader(MAX_SKILL_FILE_SIZE);
        SkillMetadata parsed = loader.parseSkillMd(content, "upload", "upload");
        if (parsed == null) {
            throw new RuntimeException("failed to parse SKILL.md: invalid frontmatter or missing name/description");
        }

        String qualifiedName = namespace + "/" + parsed.getName();
        var existing = skillCollection.findOne(Filters.eq("qualified_name", qualifiedName));
        var entity = existing.orElseGet(SkillDefinition::new);
        var previous = entity.resources;
        if (entity.id == null) {
            entity.id = new ObjectId().toHexString();
            entity.createdAt = ZonedDateTime.now();
        }
        entity.namespace = namespace;
        entity.name = parsed.getName();
        entity.qualifiedName = qualifiedName;
        entity.description = parsed.getDescription();
        entity.sourceType = SkillSourceType.UPLOAD;
        entity.content = content;
        entity.resources = writer().toResources(entity.id, content, resources);
        entity.allowedTools = parsed.getAllowedTools().isEmpty() ? null : new ArrayList<>(parsed.getAllowedTools());
        entity.metadata = parsed.getMetadata().isEmpty() ? null : Map.copyOf(parsed.getMetadata());
        entity.userId = userId;
        entity.digest = SkillDigest.of(content, resources);
        entity.updatedAt = ZonedDateTime.now();

        if (existing.isPresent()) {
            skillCollection.replace(entity);
            LOGGER.info("updated skill via upload, id={}, qualifiedName={}", entity.id, qualifiedName);
        } else {
            skillCollection.insert(entity);
            LOGGER.info("created skill via upload, id={}, qualifiedName={}", entity.id, qualifiedName);
        }
        blobStore.deleteReplaced(previous, entity.resources);
        invalidateCatalog();
        return entity;
    }

    public List<SkillDefinition> registerFromRepo(String userId, String repoUrl, String branch, String skillPath) {
        String namespace = repoManager().extractRepoOwner(repoUrl);
        if (namespace == null) {
            throw new RuntimeException("cannot extract owner from repo URL: " + repoUrl);
        }

        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory(SkillRepoManager.TEMP_DIR_PREFIX);
            repoManager().cloneRepo(repoUrl, branch, tempDir);
            String commitHash = repoManager().headCommit(tempDir);

            String effectiveSkillPath = skillPath;
            if (effectiveSkillPath == null || effectiveSkillPath.isBlank()) {
                // Auto-detect plugin format when no explicit skill path is provided
                var detector = new PluginFormatDetector();
                List<String> detectedPaths = detector.detectSkillPaths(tempDir);
                if (!detectedPaths.isEmpty()) {
                    // Use the first detected path (concatenate if multiple)
                    effectiveSkillPath = String.join(",", detectedPaths);
                    LOGGER.info("auto-detected skill path(s): {} for repo {}", effectiveSkillPath, repoUrl);
                }
            }

            List<SkillMetadata> skills;
            var loader = new SkillLoader(MAX_SKILL_FILE_SIZE);
            if (effectiveSkillPath != null && !effectiveSkillPath.isBlank()) {
                // skillPath may be comma-joined (auto-detected multiple locations), scan each separately
                skills = new ArrayList<>();
                for (String path : effectiveSkillPath.split(",")) {
                    Path scanDir = tempDir.resolve(path.trim());
                    skills.addAll(loader.loadFromSource(scanDir.toString()));
                }
            } else {
                skills = loader.loadFromSource(tempDir.toString());
            }

            var results = new ArrayList<SkillDefinition>();
            var source = new SkillRepoSource(repoUrl, branch, effectiveSkillPath, commitHash);
            for (var skill : skills) {
                var entity = repoManager().registerOrUpdate(userId, namespace, skill, source);
                results.add(entity);
            }
            LOGGER.info("registered {} skills from repo {}", results.size(), repoUrl);
            invalidateCatalog();
            return results;
        } catch (IOException e) {
            throw new RuntimeException("failed to clone repo: " + repoUrl, e);
        } finally {
            repoManager().deleteTempDir(tempDir);
        }
    }

    public List<SkillDefinition> list(SkillFilter filter, String userId, String query, String searchIn, Integer offset, Integer limit) {
        var indexedFilter = SkillQueryHelper.indexedFilter(filter);
        if (SkillQueryHelper.notInMemoryFilters(userId, query)) {
            var dbQuery = SkillQueryHelper.sortedQuery(indexedFilter);
            SkillQueryHelper.applyPaging(dbQuery, offset, limit);
            return skillCollection.find(dbQuery);
        }

        var candidates = skillCollection.find(SkillQueryHelper.sortedQuery(indexedFilter));
        var searchScope = SkillQueryHelper.normalizedSearchIn(searchIn);
        var filtered = candidates.stream()
            .filter(skill -> SkillQueryHelper.matchesUserId(skill, userId) && SkillQueryHelper.matchesQuery(skill, query, searchScope))
            .toList();
        return SkillQueryHelper.page(filtered, offset, limit);
    }

    public long count(SkillFilter filter, String userId, String query, String searchIn) {
        var indexedFilter = SkillQueryHelper.indexedFilter(filter);
        if (SkillQueryHelper.notInMemoryFilters(userId, query)) {
            return skillCollection.count(indexedFilter);
        }

        var searchScope = SkillQueryHelper.normalizedSearchIn(searchIn);
        return skillCollection.find(SkillQueryHelper.sortedQuery(indexedFilter)).stream()
            .filter(skill -> SkillQueryHelper.matchesUserId(skill, userId) && SkillQueryHelper.matchesQuery(skill, query, searchScope))
            .count();
    }

    public SkillDefinition get(String id) {
        return skillCollection.get(id)
            .orElseThrow(() -> new NotFoundException("skill not found, id=" + id));
    }

    public SkillDefinition findByQualifiedName(String qualifiedName) {
        return skillCollection.findOne(Filters.eq("qualified_name", qualifiedName))
            .orElseThrow(() -> new NotFoundException("skill not found: " + qualifiedName));
    }

    public void delete(String id) {
        var entity = skillCollection.get(id).orElse(null);
        skillCollection.delete(id);
        if (entity != null) blobStore.deleteReplaced(entity.resources, null);
        invalidateCatalog();
        LOGGER.info("deleted skill, id={}", id);
    }

    public SkillDefinition update(String id, String description, String content, List<String> allowedTools, List<SkillResourceUpdate> updates) {
        var entity = get(id);
        if (description != null) entity.description = description;
        if (allowedTools != null) entity.allowedTools = allowedTools.isEmpty() ? null : allowedTools;
        if (content != null) entity.content = content;

        var previous = entity.resources;
        if (updates != null) {
            var merged = writer().merge(id, previous, updates);
            writer().enforceBudget(id, entity.content, merged);
            entity.resources = merged.isEmpty() ? null : merged;
        }
        if (digestStale(entity.digest, content != null, previous, entity.resources)) {
            entity.digest = SkillDigest.of(entity.content, digestBytes(entity.resources));
        }
        entity.updatedAt = ZonedDateTime.now();
        skillCollection.replace(entity);
        blobStore.deleteReplaced(previous, entity.resources);
        invalidateCatalog();
        return entity;
    }

    private Map<String, byte[]> digestBytes(List<SkillResource> resources) {
        var bytes = new LinkedHashMap<String, byte[]>();
        if (resources == null) return bytes;
        for (var resource : resources) {
            bytes.put(resource.path, resource.content != null
                    ? resource.content.getBytes(StandardCharsets.UTF_8)
                    : blobStore.fetch(resource.storagePath));
        }
        return bytes;
    }

    long sweepStaleRepoTempDirs() {
        return repoManager().sweepStaleTempDirs();
    }

    public SkillDefinition syncFromRepo(String id) {
        return syncFromRepo(id, true);
    }

    private SkillDefinition syncFromRepo(String id, boolean force) {
        var entity = get(id);
        if (entity.sourceType != SkillSourceType.REPO || entity.repoConfig == null) {
            throw new RuntimeException("skill is not from a repo, id=" + id);
        }
        var config = entity.repoConfig;
        String remoteHead = force ? null : repoManager().remoteHead(config.repoUrl, config.branch);
        if (remoteHead != null && remoteHead.equals(config.lastCommitHash)) {
            LOGGER.info("skill repo unchanged, sync skipped, id={}, qualifiedName={}, commit={}", entity.id, entity.qualifiedName, remoteHead);
            return entity;
        }
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory(SkillRepoManager.SYNC_TEMP_DIR_PREFIX);
            repoManager().cloneRepo(config.repoUrl, config.branch, tempDir);
            String commitHash = remoteHead != null ? remoteHead : repoManager().headCommit(tempDir);

            String effectiveSkillPath = config.skillPath;
            if (effectiveSkillPath == null || effectiveSkillPath.isBlank()) {
                var detector = new PluginFormatDetector();
                List<String> detectedPaths = detector.detectSkillPaths(tempDir);
                if (!detectedPaths.isEmpty()) {
                    effectiveSkillPath = String.join(",", detectedPaths);
                }
            }

            List<SkillMetadata> skills;
            var loader = new SkillLoader(MAX_SKILL_FILE_SIZE);
            if (effectiveSkillPath != null && !effectiveSkillPath.isBlank()) {
                skills = new ArrayList<>();
                for (String path : effectiveSkillPath.split(",")) {
                    Path scanDir = tempDir.resolve(path.trim());
                    skills.addAll(loader.loadFromSource(scanDir.toString()));
                }
            } else {
                skills = loader.loadFromSource(tempDir.toString());
            }

            for (var skill : skills) {
                if (skill.getName().equals(entity.name)) {
                    syncMatchedSkill(entity, skill, commitHash);
                    LOGGER.info("synced skill from repo, id={}, qualifiedName={}, commit={}", entity.id, entity.qualifiedName, commitHash);
                    return entity;
                }
            }
            throw new RuntimeException("skill not found in repo after sync, name=" + entity.name);
        } catch (IOException e) {
            throw new RuntimeException("failed to sync repo: " + config.repoUrl, e);
        } finally {
            repoManager().deleteTempDir(tempDir);
        }
    }

    SkillDefinition syncFromRepoIfChanged(String id) {
        return syncFromRepo(id, false);
    }

    private void syncMatchedSkill(SkillDefinition entity, SkillMetadata skill, String commitHash) {
        var skillDir = skill.getSkillDir() != null
            ? Path.of(skill.getSkillDir())
            : Path.of(skill.getPath()).getParent();
        if (skillDir == null) {
            throw new RuntimeException("cannot determine skill directory for " + skill.getName() + ", path=" + skill.getPath());
        }
        var previous = entity.resources;
        entity.content = repoManager().readSkillMdFromDir(skillDir);
        var bytes = repoManager().readResourceBytes(skillDir, skill.getResources());
        entity.resources = writer().toResources(entity.id, entity.content, bytes);
        entity.description = skill.getDescription();
        entity.allowedTools = skill.getAllowedTools().isEmpty() ? null : new ArrayList<>(skill.getAllowedTools());
        entity.metadata = skill.getMetadata().isEmpty() ? null : Map.copyOf(skill.getMetadata());
        entity.digest = SkillDigest.of(entity.content, bytes);
        if (commitHash != null) entity.repoConfig.lastCommitHash = commitHash;
        entity.repoConfig.lastSyncedAt = ZonedDateTime.now();
        entity.updatedAt = ZonedDateTime.now();
        skillCollection.replace(entity);
        blobStore.deleteReplaced(previous, entity.resources);
        invalidateCatalog();
    }

    public SkillDefinition download(String id) {
        return get(id);
    }

    SkillRepoManager repoManager() {
        return new SkillRepoManager(skillCollection, blobStore);
    }

    private SkillResourceWriter writer() {
        return new SkillResourceWriter(blobStore);
    }

    public Map<String, String> batchResolve(Set<String> skillIds) {
        var cleanIds = IdLists.clean(new ArrayList<>(skillIds));
        if (cleanIds.isEmpty()) return Map.of();
        var result = new HashMap<String, String>();
        for (var def : skillCollection.find(Filters.in("_id", cleanIds.toArray(new String[0])))) {
            result.put(def.id, def.name);
        }
        return result;
    }

    public List<SkillMetadata> resolveSkills(List<String> skillIds) {
        var cleanSkillIds = IdLists.clean(skillIds);
        if (cleanSkillIds.isEmpty()) return List.of();
        var result = new ArrayList<SkillMetadata>();
        for (var id : cleanSkillIds) {
            skillCollection.get(id).ifPresent(def -> result.add(toMetadata(def)));
        }
        return result;
    }

    // Skills are shared catalog entries. Keep callerUserId in the API because callers still use it for Agent/session
    // authorization, but Skill resolution itself only requires that every referenced Skill exists.
    public List<SkillMetadata> resolveAccessibleSkills(List<String> skillIds, String callerUserId) {
        var cleanSkillIds = IdLists.clean(skillIds);
        if (cleanSkillIds.isEmpty()) return List.of();
        var result = new ArrayList<SkillMetadata>(cleanSkillIds.size());
        for (var id : cleanSkillIds) {
            var definition = skillCollection.get(id).orElseThrow(SkillService::unavailableSkill);
            result.add(toMetadata(definition));
        }
        return result;
    }

    public SkillMetadata toMetadata(SkillDefinition def) {
        var resourcePaths = def.resources != null
            ? def.resources.stream().map(r -> r.path).toList()
            : Collections.<String>emptyList();
        return SkillMetadata.builder(def.name, def.description != null ? def.description : "", null)
            .namespace(def.namespace)
            .content(def.content)
            .allowedTools(def.allowedTools != null ? def.allowedTools : Collections.emptyList())
            .metadata(def.metadata != null ? def.metadata : Collections.emptyMap())
            .resources(resourcePaths)
            .build();
    }
}
