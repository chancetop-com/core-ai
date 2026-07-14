package ai.core.server.skill;

import ai.core.server.domain.SkillDefinition;
import ai.core.server.domain.SkillResource;
import ai.core.server.domain.SkillSourceType;
import ai.core.server.util.IdLists;
import ai.core.skill.SkillLoader;
import ai.core.skill.SkillMetadata;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import core.framework.web.exception.NotFoundException;
import org.bson.conversions.Bson;
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
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * @author stephen
 */
public class SkillService {
    private static final Logger LOGGER = LoggerFactory.getLogger(SkillService.class);
    private static final int MAX_SKILL_FILE_SIZE = 10 * 1024 * 1024;
    private static final String SEARCH_IN_NAME_DESCRIPTION = "name_description";
    private static final String SEARCH_IN_NAME = "name";
    private static final String SEARCH_IN_METADATA = "metadata";
    private static final String SEARCH_IN_CONTENT = "content";

    @Inject
    MongoCollection<SkillDefinition> skillCollection;

    private final SkillRepoManager repoManager;

    public SkillService() {
        this.repoManager = new SkillRepoManager(skillCollection);
    }

    public String extractRepoOwner(String repoUrl) {
        return repoManager.extractRepoOwner(repoUrl);
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
        entity.updatedAt = ZonedDateTime.now();

        if (existing.isPresent()) {
            skillCollection.replace(entity);
            LOGGER.info("updated skill via upload, id={}, qualifiedName={}", entity.id, qualifiedName);
        } else {
            skillCollection.insert(entity);
            LOGGER.info("created skill via upload, id={}, qualifiedName={}", entity.id, qualifiedName);
        }
        return entity;
    }

    public List<SkillDefinition> registerFromRepo(String userId, String repoUrl, String branch, String skillPath) {
        String namespace = repoManager.extractRepoOwner(repoUrl);
        if (namespace == null) {
            throw new RuntimeException("cannot extract owner from repo URL: " + repoUrl);
        }

        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("skill-repo-");
            repoManager.cloneRepo(repoUrl, branch, tempDir);

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
            if (effectiveSkillPath != null && !effectiveSkillPath.isBlank() && !effectiveSkillPath.equals(skillPath)) {
                // Auto-detected: scan each detected path separately
                skills = new ArrayList<>();
                for (String path : effectiveSkillPath.split(",")) {
                    Path scanDir = tempDir.resolve(path.trim());
                    skills.addAll(loader.loadFromSource(scanDir.toString()));
                }
            } else {
                Path scanDir = effectiveSkillPath != null && !effectiveSkillPath.isBlank()
                    ? tempDir.resolve(effectiveSkillPath)
                    : tempDir;
                skills = loader.loadFromSource(scanDir.toString());
            }

            var results = new ArrayList<SkillDefinition>();
            for (var skill : skills) {
                var entity = repoManager.registerOrUpdate(userId, namespace, skill, repoUrl, branch, effectiveSkillPath);
                results.add(entity);
            }
            LOGGER.info("registered {} skills from repo {}", results.size(), repoUrl);
            return results;
        } catch (IOException e) {
            throw new RuntimeException("failed to clone repo: " + repoUrl, e);
        } finally {
            repoManager.deleteTempDir(tempDir);
        }
    }

    public List<SkillDefinition> list(SkillFilter filter, String userId, String query, String searchIn, Integer offset, Integer limit) {
        var indexedFilter = indexedFilter(filter);
        if (notInMemoryFilters(userId, query)) {
            var dbQuery = sortedQuery(indexedFilter);
            applyPaging(dbQuery, offset, limit);
            return skillCollection.find(dbQuery);
        }

        var candidates = skillCollection.find(sortedQuery(indexedFilter));
        var searchScope = normalizedSearchIn(searchIn);
        var filtered = candidates.stream()
            .filter(skill -> matchesUserId(skill, userId))
            .filter(skill -> matchesQuery(skill, query, searchScope))
            .toList();
        return page(filtered, offset, limit);
    }

    public long count(SkillFilter filter, String userId, String query, String searchIn) {
        var indexedFilter = indexedFilter(filter);
        if (notInMemoryFilters(userId, query)) {
            return skillCollection.count(indexedFilter);
        }

        var searchScope = normalizedSearchIn(searchIn);
        return skillCollection.find(sortedQuery(indexedFilter)).stream()
            .filter(skill -> matchesUserId(skill, userId))
            .filter(skill -> matchesQuery(skill, query, searchScope))
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
        LOGGER.info("deleted skill, id={}", id);
    }

    public SkillDefinition update(String id, String description, String content, List<String> allowedTools, List<SkillResource> resources) {
        var entity = get(id);
        if (description != null) entity.description = description;
        if (content != null) entity.content = content;
        if (resources != null) entity.resources = resources.isEmpty() ? null : resources;
        if (allowedTools != null) entity.allowedTools = allowedTools.isEmpty() ? null : allowedTools;
        entity.updatedAt = ZonedDateTime.now();
        skillCollection.replace(entity);
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
            repoManager.cloneRepo(config.repoUrl, config.branch, tempDir);

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
            repoManager.deleteTempDir(tempDir);
        }
    }

    private void syncMatchedSkill(SkillDefinition entity, SkillMetadata skill) {
        var skillDir = skill.getSkillDir() != null
            ? Path.of(skill.getSkillDir())
            : Path.of(skill.getPath()).getParent();
        entity.content = repoManager.readSkillMdFromDir(skillDir);
        entity.resources = repoManager.readResourcesFromDir(skillDir, skill.getResources());
        entity.description = skill.getDescription();
        entity.allowedTools = skill.getAllowedTools().isEmpty() ? null : new ArrayList<>(skill.getAllowedTools());
        entity.metadata = skill.getMetadata().isEmpty() ? null : Map.copyOf(skill.getMetadata());
        entity.repoConfig.lastSyncedAt = ZonedDateTime.now();
        entity.updatedAt = ZonedDateTime.now();
        skillCollection.replace(entity);
    }

    public SkillDefinition download(String id) {
        return get(id);
    }

    private Bson indexedFilter(SkillFilter filter) {
        var filters = new ArrayList<Bson>();
        if (filter.namespace() != null && !filter.namespace().isBlank()) {
            filters.add(Filters.eq("namespace", filter.namespace()));
        }
        if (filter.sourceType() != null && !filter.sourceType().isBlank()) {
            filters.add(Filters.eq("source_type", SkillSourceType.valueOf(filter.sourceType())));
        }
        return filters.isEmpty() ? Filters.empty() : Filters.and(filters);
    }

    private Query sortedQuery(Bson filter) {
        var dbQuery = new Query();
        dbQuery.filter = filter;
        dbQuery.sort = Sorts.descending("updated_at");
        return dbQuery;
    }

    private void applyPaging(Query dbQuery, Integer offset, Integer limit) {
        if (offset != null || limit != null) {
            dbQuery.skip = Math.max(0, offset != null ? offset : 0);
            dbQuery.limit = normalizedLimit(limit);
        }
    }

    private int normalizedLimit(Integer limit) {
        return Math.clamp(limit != null ? limit : 20, 1, 100);
    }

    private List<SkillDefinition> page(List<SkillDefinition> skills, Integer offset, Integer limit) {
        if (offset == null && limit == null) {
            return skills;
        }

        int start = Math.max(0, offset != null ? offset : 0);
        if (start >= skills.size()) {
            return List.of();
        }

        int end = Math.min(skills.size(), start + normalizedLimit(limit));
        return skills.subList(start, end);
    }

    private boolean notInMemoryFilters(String userId, String query) {
        return noText(userId) && noText(query);
    }

    private boolean matchesUserId(SkillDefinition skill, String userId) {
        if (noText(userId)) return true;
        return containsIgnoreCase(skill.userId, userId.trim());
    }

    private boolean matchesQuery(SkillDefinition skill, String query, String searchIn) {
        if (noText(query)) return true;

        var needle = query.trim();
        return switch (searchIn) {
            case SEARCH_IN_NAME -> matchesName(skill, needle);
            case SEARCH_IN_METADATA -> matchesMetadata(skill, needle);
            case SEARCH_IN_CONTENT -> containsIgnoreCase(skill.content, needle);
            default -> matchesName(skill, needle) || containsIgnoreCase(skill.description, needle);
        };
    }

    private boolean matchesName(SkillDefinition skill, String needle) {
        return containsIgnoreCase(skill.name, needle) || containsIgnoreCase(skill.qualifiedName, needle);
    }

    private boolean matchesMetadata(SkillDefinition skill, String needle) {
        if (matchesName(skill, needle)) return true;
        if (containsIgnoreCase(skill.description, needle)) return true;
        if (containsIgnoreCase(skill.namespace, needle)) return true;
        if (skill.sourceType != null && containsIgnoreCase(skill.sourceType.name(), needle)) return true;
        if (containsIgnoreCase(skill.userId, needle)) return true;
        if (containsIgnoreCase(skill.version, needle)) return true;
        if (skill.metadata != null) {
            for (var entry : skill.metadata.entrySet()) {
                if (containsIgnoreCase(entry.getKey(), needle) || containsIgnoreCase(entry.getValue(), needle)) return true;
            }
        }
        if (skill.allowedTools != null) {
            for (var tool : skill.allowedTools) {
                if (containsIgnoreCase(tool, needle)) return true;
            }
        }
        return false;
    }

    private String normalizedSearchIn(String searchIn) {
        if (noText(searchIn)) return SEARCH_IN_NAME_DESCRIPTION;
        var value = searchIn.trim().toLowerCase(Locale.getDefault());
        return switch (value) {
            case SEARCH_IN_NAME, SEARCH_IN_NAME_DESCRIPTION, SEARCH_IN_METADATA, SEARCH_IN_CONTENT -> value;
            default -> SEARCH_IN_NAME_DESCRIPTION;
        };
    }

    private boolean noText(String value) {
        return value == null || value.isBlank();
    }

    private boolean containsIgnoreCase(String value, String needle) {
        if (value == null || needle.isEmpty() || needle.length() > value.length()) return false;
        for (int i = 0; i <= value.length() - needle.length(); i++) {
            if (value.regionMatches(true, i, needle, 0, needle.length())) {
                return true;
            }
        }
        return false;
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
