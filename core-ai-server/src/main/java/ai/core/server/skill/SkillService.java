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
import java.util.Collections;
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

    @Inject
    SkillStorage skillStorage;

    public SkillDefinition upload(String userId, String namespace, byte[] skillFileBytes, Map<String, byte[]> resources) {
        String content = new String(skillFileBytes, StandardCharsets.UTF_8);
        var loader = new SkillLoader(MAX_SKILL_FILE_SIZE);
        SkillMetadata parsed = loader.parseSkillMd(content, "upload", "upload");
        if (parsed == null) {
            throw new RuntimeException("failed to parse SKILL.md: invalid frontmatter or missing name/description");
        }

        String qualifiedName = namespace + "/" + parsed.getName();
        skillStorage.writeSkillMd(namespace, parsed.getName(), content);
        if (resources != null && !resources.isEmpty()) {
            skillStorage.writeResources(namespace, parsed.getName(), resources);
        }

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
        entity.content = null;
        entity.resources = null;
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
        var entity = get(id);
        try {
            skillStorage.delete(entity.namespace, entity.name);
        } catch (Exception e) {
            LOGGER.warn("failed to delete skill files for {}, continuing with DB delete", entity.qualifiedName, e);
        }
        skillCollection.delete(id);
        LOGGER.info("deleted skill, id={}", id);
    }

    public SkillDefinition update(String id, String description, String content, List<String> allowedTools, List<SkillResource> resources) {
        var entity = get(id);
        if (description != null) entity.description = description;
        if (content != null) {
            skillStorage.writeSkillMd(entity.namespace, entity.name, content);
        }
        if (resources != null) {
            for (var r : resources) {
                skillStorage.writeResource(entity.namespace, entity.name, r.path,
                    r.content != null ? r.content.getBytes(StandardCharsets.UTF_8) : new byte[0]);
            }
        }
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
                    skillStorage.copyDirectory(entity.namespace, entity.name, skillDir);

                    entity.description = skill.getDescription();
                    entity.allowedTools = skill.getAllowedTools().isEmpty() ? null : new ArrayList<>(skill.getAllowedTools());
                    entity.metadata = skill.getMetadata().isEmpty() ? null : Map.copyOf(skill.getMetadata());
                    entity.content = null;
                    entity.resources = null;
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
        var entity = get(id);
        if (!skillStorage.exists(entity.namespace, entity.name)) {
            return entity;
        }
        if (entity.content == null) {
            entity.content = skillStorage.readSkillMd(entity.namespace, entity.name);
        }
        if (entity.resources == null) {
            var paths = skillStorage.listResources(entity.namespace, entity.name);
            if (!paths.isEmpty()) {
                var loaded = new ArrayList<SkillResource>(paths.size());
                for (var path : paths) {
                    var resource = new SkillResource();
                    resource.path = path;
                    resource.content = new String(
                        skillStorage.readResource(entity.namespace, entity.name, path),
                        StandardCharsets.UTF_8);
                    loaded.add(resource);
                }
                entity.resources = loaded;
            }
        }
        return entity;
    }

    public List<SkillMetadata> resolveSkills(List<String> skillIds) {
        if (skillIds == null || skillIds.isEmpty()) return List.of();
        var result = new ArrayList<SkillMetadata>();
        for (var id : skillIds) {
            skillCollection.get(id).ifPresent(def -> result.add(toMetadata(def)));
        }
        return result;
    }

    public SkillMetadata toMetadata(SkillDefinition def) {
        var skillDir = skillStorage.skillDir(def.namespace, def.name);
        var content = skillStorage.exists(def.namespace, def.name)
            ? skillStorage.readSkillMd(def.namespace, def.name)
            : def.content;
        var resources = skillStorage.exists(def.namespace, def.name)
            ? skillStorage.listResources(def.namespace, def.name)
            : def.resources != null ? def.resources.stream().map(r -> r.path).toList() : Collections.<String>emptyList();
        return SkillMetadata.builder(def.name, def.description != null ? def.description : "", null)
            .namespace(def.namespace)
            .skillDir(skillDir.toString())
            .content(content)
            .allowedTools(def.allowedTools != null ? def.allowedTools : Collections.emptyList())
            .metadata(def.metadata != null ? def.metadata : Collections.emptyMap())
            .resources(resources)
            .build();
    }

    private SkillDefinition registerOrUpdate(String userId, String namespace, SkillMetadata skill, String repoUrl, String branch, String skillPath) {
        String qualifiedName = namespace + "/" + skill.getName();
        Path skillDir = skill.getSkillDir() != null
            ? Path.of(skill.getSkillDir())
            : Path.of(skill.getPath()).getParent();
        skillStorage.copyDirectory(namespace, skill.getName(), skillDir);

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
        entity.content = null;
        entity.resources = null;
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
