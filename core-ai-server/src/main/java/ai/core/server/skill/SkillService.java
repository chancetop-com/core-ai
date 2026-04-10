package ai.core.server.skill;

import ai.core.server.domain.SkillDefinition;
import ai.core.server.domain.SkillRepoConfig;
import ai.core.server.domain.SkillResource;
import ai.core.server.domain.SkillSourceType;
import ai.core.skill.SkillLoader;
import ai.core.skill.SkillMetadata;
import com.mongodb.client.model.Filters;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
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
import java.util.List;
import java.util.Map;
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
        entity.allowedTools = parsed.getAllowedTools().isEmpty() ? null : new ArrayList<>(parsed.getAllowedTools());
        entity.metadata = parsed.getMetadata().isEmpty() ? null : Map.copyOf(parsed.getMetadata());
        entity.resources = buildResources(resources);

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
                var entity = createOrUpdateFromParsed(userId, namespace, skill, repoUrl, branch, skillPath);
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

    public List<SkillDefinition> list(String namespace, String sourceType, String query) {
        var filters = new ArrayList<Bson>();
        if (namespace != null && !namespace.isBlank()) {
            filters.add(Filters.eq("namespace", namespace));
        }
        if (sourceType != null && !sourceType.isBlank()) {
            filters.add(Filters.eq("source_type", SkillSourceType.valueOf(sourceType)));
        }
        if (query != null && !query.isBlank()) {
            filters.add(Filters.or(
                Filters.regex("name", query, "i"),
                Filters.regex("description", query, "i"),
                Filters.regex("namespace", query, "i")
            ));
        }

        Bson filter = filters.isEmpty() ? Filters.exists("_id") : Filters.and(filters);
        return skillCollection.find(filter);
    }

    public SkillDefinition get(String id) {
        return skillCollection.get(id)
            .orElseThrow(() -> new RuntimeException("skill not found, id=" + id));
    }

    public void delete(String id) {
        skillCollection.delete(id);
        LOGGER.info("deleted skill, id={}", id);
    }

    public SkillDefinition update(String id, String description, String content, List<String> allowedTools, List<SkillResource> resources) {
        var entity = get(id);
        if (description != null) entity.description = description;
        if (content != null) entity.content = content;
        if (allowedTools != null) entity.allowedTools = allowedTools.isEmpty() ? null : allowedTools;
        if (resources != null) entity.resources = resources.isEmpty() ? null : resources;
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
                    entity.content = Files.readString(Path.of(skill.getPath()), StandardCharsets.UTF_8);
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

    public List<SkillMetadata> resolveSkills(List<String> skillIds) {
        if (skillIds == null || skillIds.isEmpty()) return List.of();
        var result = new ArrayList<SkillMetadata>();
        for (var id : skillIds) {
            skillCollection.get(id).ifPresent(def -> {
                var metadata = SkillMetadata.builder(def.name, def.description != null ? def.description : "", null)
                    .namespace(def.namespace)
                    .content(def.content)
                    .allowedTools(def.allowedTools)
                    .metadata(def.metadata)
                    .build();
                result.add(metadata);
            });
        }
        return result;
    }

    private SkillDefinition createOrUpdateFromParsed(String userId, String namespace, SkillMetadata skill, String repoUrl, String branch, String skillPath) {
        String qualifiedName = namespace + "/" + skill.getName();
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
        try {
            entity.content = Files.readString(Path.of(skill.getPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("failed to read skill file: " + skill.getPath(), e);
        }
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

    private List<SkillResource> buildResources(Map<String, byte[]> resources) {
        if (resources == null || resources.isEmpty()) return null;
        var result = new ArrayList<SkillResource>();
        for (var entry : resources.entrySet()) {
            var resource = new SkillResource();
            resource.path = entry.getKey();
            resource.content = new String(entry.getValue(), StandardCharsets.UTF_8);
            result.add(resource);
        }
        return result;
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
