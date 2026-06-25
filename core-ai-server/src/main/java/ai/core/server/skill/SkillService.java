package ai.core.server.skill;

import ai.core.server.domain.SkillDefinition;
import ai.core.server.domain.SkillRepoConfig;
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
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author stephen
 */
public class SkillService {
    private static final Logger LOGGER = LoggerFactory.getLogger(SkillService.class);
    private static final int MAX_SKILL_FILE_SIZE = 10 * 1024 * 1024;
    private static final Pattern REPO_OWNER_PATTERN = Pattern.compile("https?://[^/]+/([^/]+)/");

    @Inject
    MongoCollection<SkillDefinition> skillCollection;

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
        String namespace = extractRepoOwner(repoUrl);
        if (namespace == null) {
            throw new RuntimeException("cannot extract owner from repo URL: " + repoUrl);
        }

        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("skill-repo-");
            cloneRepo(repoUrl, branch, tempDir);

            Path scanDir = skillPath != null && !skillPath.isBlank()
                ? tempDir.resolve(skillPath)
                : tempDir;

            var loader = new SkillLoader(MAX_SKILL_FILE_SIZE);
            var skills = loader.loadFromSource(scanDir.toString());

            var results = new ArrayList<SkillDefinition>();
            for (var skill : skills) {
                var entity = registerOrUpdate(userId, namespace, skill, repoUrl, branch, skillPath);
                results.add(entity);
            }
            LOGGER.info("registered {} skills from repo {}", results.size(), repoUrl);
            return results;
        } catch (IOException e) {
            throw new RuntimeException("failed to clone repo: " + repoUrl, e);
        } finally {
            deleteTempDir(tempDir);
        }
    }

    public List<SkillDefinition> list(String namespace, String sourceType, String userId, String query, Integer offset, Integer limit) {
        var dbQuery = new Query();
        dbQuery.filter = filter(namespace, sourceType, userId, query);
        dbQuery.sort = Sorts.descending("updated_at");
        if (offset != null || limit != null) {
            dbQuery.skip = Math.max(0, offset != null ? offset : 0);
            dbQuery.limit = Math.min(Math.max(limit != null ? limit : 20, 1), 100);
        }
        return skillCollection.find(dbQuery);
    }

    public long count(String namespace, String sourceType, String userId, String query) {
        return skillCollection.count(filter(namespace, sourceType, userId, query));
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
            cloneRepo(config.repoUrl, config.branch, tempDir);

            Path scanDir = config.skillPath != null && !config.skillPath.isBlank()
                ? tempDir.resolve(config.skillPath)
                : tempDir;

            var loader = new SkillLoader(MAX_SKILL_FILE_SIZE);
            var skills = loader.loadFromSource(scanDir.toString());

            for (var skill : skills) {
                if (skill.getName().equals(entity.name)) {
                    Path skillDir = skill.getSkillDir() != null
                        ? Path.of(skill.getSkillDir())
                        : Path.of(skill.getPath()).getParent();
                    entity.content = readSkillMdFromDir(skillDir);
                    entity.resources = readResourcesFromDir(skillDir, skill.getResources());
                    entity.description = skill.getDescription();
                    entity.allowedTools = skill.getAllowedTools().isEmpty() ? null : new ArrayList<>(skill.getAllowedTools());
                    entity.metadata = skill.getMetadata().isEmpty() ? null : Map.copyOf(skill.getMetadata());
                    entity.repoConfig.lastSyncedAt = ZonedDateTime.now();
                    entity.updatedAt = ZonedDateTime.now();
                    skillCollection.replace(entity);
                    LOGGER.info("synced skill from repo, id={}, qualifiedName={}", entity.id, entity.qualifiedName);
                    return entity;
                }
            }
            throw new RuntimeException("skill not found in repo after sync, name=" + entity.name);
        } catch (IOException e) {
            throw new RuntimeException("failed to sync repo: " + config.repoUrl, e);
        } finally {
            deleteTempDir(tempDir);
        }
    }

    public SkillDefinition download(String id) {
        return get(id);
    }

    private Bson filter(String namespace, String sourceType, String userId, String query) {
        var filters = new ArrayList<Bson>();
        if (namespace != null && !namespace.isBlank()) {
            filters.add(Filters.eq("namespace", namespace));
        }
        if (sourceType != null && !sourceType.isBlank()) {
            filters.add(Filters.eq("source_type", SkillSourceType.valueOf(sourceType)));
        }
        if (userId != null && !userId.isBlank()) {
            filters.add(Filters.regex("user_id", Pattern.quote(userId), "i"));
        }
        if (query != null && !query.isBlank()) {
            var pattern = Pattern.quote(query.trim());
            filters.add(Filters.or(
                Filters.regex("name", pattern, "i"),
                Filters.regex("qualified_name", pattern, "i"),
                Filters.regex("description", pattern, "i"),
                Filters.regex("namespace", pattern, "i"),
                Filters.regex("source_type", pattern, "i"),
                Filters.regex("user_id", pattern, "i"),
                Filters.regex("version", pattern, "i"),
                Filters.regex("allowed_tools", pattern, "i")
            ));
        }
        return filters.isEmpty() ? Filters.empty() : Filters.and(filters);
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

    private SkillDefinition registerOrUpdate(String userId, String namespace, SkillMetadata skill, String repoUrl, String branch, String skillPath) {
        String qualifiedName = namespace + "/" + skill.getName();
        Path skillDir = skill.getSkillDir() != null
            ? Path.of(skill.getSkillDir())
            : Path.of(skill.getPath()).getParent();

        var existing = skillCollection.findOne(Filters.eq("qualified_name", qualifiedName));
        var entity = existing.orElseGet(SkillDefinition::new);
        if (entity.id == null) {
            entity.id = new ObjectId().toHexString();
            entity.createdAt = ZonedDateTime.now();
        }
        entity.namespace = namespace;
        entity.name = skill.getName();
        entity.qualifiedName = qualifiedName;
        entity.description = skill.getDescription();
        entity.sourceType = SkillSourceType.REPO;
        entity.content = readSkillMdFromDir(skillDir);
        entity.resources = readResourcesFromDir(skillDir, skill.getResources());
        entity.allowedTools = skill.getAllowedTools().isEmpty() ? null : new ArrayList<>(skill.getAllowedTools());
        entity.metadata = skill.getMetadata().isEmpty() ? null : Map.copyOf(skill.getMetadata());
        entity.userId = userId;
        entity.updatedAt = ZonedDateTime.now();

        var repoConfig = new SkillRepoConfig();
        repoConfig.repoUrl = repoUrl;
        repoConfig.branch = branch != null ? branch : "main";
        repoConfig.skillPath = skillPath;
        repoConfig.lastSyncedAt = ZonedDateTime.now();
        entity.repoConfig = repoConfig;

        if (existing.isPresent()) {
            skillCollection.replace(entity);
        } else {
            skillCollection.insert(entity);
        }
        return entity;
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

    private String readSkillMdFromDir(Path skillDir) {
        try {
            return Files.readString(skillDir.resolve("SKILL.md"), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("failed to read SKILL.md from " + skillDir, e);
        }
    }

    private List<SkillResource> readResourcesFromDir(Path skillDir, List<String> paths) {
        if (paths == null || paths.isEmpty()) return null;
        var list = new ArrayList<SkillResource>(paths.size());
        for (var relPath : paths) {
            try {
                var bytes = Files.readAllBytes(skillDir.resolve(relPath));
                var r = new SkillResource();
                r.path = relPath;
                r.content = new String(bytes, StandardCharsets.UTF_8);
                list.add(r);
            } catch (IOException e) {
                LOGGER.warn("failed to read resource {} in {}", relPath, skillDir, e);
            }
        }
        return list.isEmpty() ? null : list;
    }

    String extractRepoOwner(String repoUrl) {
        Matcher matcher = REPO_OWNER_PATTERN.matcher(repoUrl);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private void cloneRepo(String repoUrl, String branch, Path targetDir) throws IOException {
        String branchArg = branch != null ? branch : "main";
        var process = new ProcessBuilder("git", "clone", "--depth", "1", "--branch", branchArg, repoUrl, targetDir.toString())
            .redirectErrorStream(true)
            .start();
        try {
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                throw new IOException("git clone failed (exit=" + exitCode + "): " + output);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("git clone interrupted", e);
        }
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    private void deleteTempDir(Path dir) {
        if (dir == null) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                .forEach(path -> path.toFile().delete());
        } catch (IOException e) {
            LOGGER.warn("failed to delete temp dir: {}", dir, e);
        }
    }
}
