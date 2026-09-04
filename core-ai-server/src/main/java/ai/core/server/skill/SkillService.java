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

    @Inject
    MongoCollection<SkillDefinition> skillCollection;

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
        entity.resources = toResources(resources);
        entity.allowedTools = parsed.getAllowedTools().isEmpty() ? null : new ArrayList<>(parsed.getAllowedTools());
        entity.metadata = parsed.getMetadata().isEmpty() ? null : Map.copyOf(parsed.getMetadata());
        entity.userId = userId;
        entity.digest = SkillDigest.of(content, entity.resources);
        entity.updatedAt = ZonedDateTime.now();

        if (existing.isPresent()) {
            skillCollection.replace(entity);
            LOGGER.info("updated skill via upload, id={}, qualifiedName={}", entity.id, qualifiedName);
        } else {
            skillCollection.insert(entity);
            LOGGER.info("created skill via upload, id={}, qualifiedName={}", entity.id, qualifiedName);
        }
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
            tempDir = Files.createTempDirectory("skill-repo-");
            repoManager().cloneRepo(repoUrl, branch, tempDir);

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
            for (var skill : skills) {
                var entity = repoManager().registerOrUpdate(userId, namespace, skill, repoUrl, branch, effectiveSkillPath);
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
        skillCollection.delete(id);
        invalidateCatalog();
        LOGGER.info("deleted skill, id={}", id);
    }

    public SkillDefinition update(String id, String description, String content, List<String> allowedTools, List<SkillResource> resources) {
        var entity = get(id);
        if (description != null) entity.description = description;
        if (content != null) entity.content = content;
        if (resources != null) entity.resources = resources.isEmpty() ? null : resources;
        if (allowedTools != null) entity.allowedTools = allowedTools.isEmpty() ? null : allowedTools;
        entity.digest = SkillDigest.of(entity.content, entity.resources);
        entity.updatedAt = ZonedDateTime.now();
        skillCollection.replace(entity);
        invalidateCatalog();
        return entity;
    }

    public SkillDefinition syncFromRepo(String id) {
        var entity = get(id);
        if (entity.sourceType != SkillSourceType.REPO || entity.repoConfig == null) {
            throw new RuntimeException("skill is not from a repo, id=" + id);
        }
        var config = entity.repoConfig;
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("skill-repo-sync-");
            repoManager().cloneRepo(config.repoUrl, config.branch, tempDir);

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
                    syncMatchedSkill(entity, skill);
                    LOGGER.info("synced skill from repo, id={}, qualifiedName={}", entity.id, entity.qualifiedName);
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

    private void syncMatchedSkill(SkillDefinition entity, SkillMetadata skill) {
        var skillDir = skill.getSkillDir() != null
            ? Path.of(skill.getSkillDir())
            : Path.of(skill.getPath()).getParent();
        if (skillDir == null) {
            throw new RuntimeException("cannot determine skill directory for " + skill.getName() + ", path=" + skill.getPath());
        }
        entity.content = repoManager().readSkillMdFromDir(skillDir);
        entity.resources = repoManager().readResourcesFromDir(skillDir, skill.getResources());
        entity.description = skill.getDescription();
        entity.allowedTools = skill.getAllowedTools().isEmpty() ? null : new ArrayList<>(skill.getAllowedTools());
        entity.metadata = skill.getMetadata().isEmpty() ? null : Map.copyOf(skill.getMetadata());
        entity.digest = SkillDigest.of(entity.content, entity.resources);
        entity.repoConfig.lastSyncedAt = ZonedDateTime.now();
        entity.updatedAt = ZonedDateTime.now();
        skillCollection.replace(entity);
        invalidateCatalog();
    }

    public SkillDefinition download(String id) {
        return get(id);
    }

    private SkillRepoManager repoManager() {
        return new SkillRepoManager(skillCollection);
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

    private List<SkillResource> toResources(Map<String, byte[]> resources) {
        if (resources == null || resources.isEmpty()) return null;
        var list = new ArrayList<SkillResource>(resources.size());
        for (var entry : resources.entrySet()) {
            var r = new SkillResource();
            r.path = entry.getKey();
            r.content = new String(entry.getValue(), StandardCharsets.UTF_8);
            list.add(r);
        }
        return list;
    }
}
