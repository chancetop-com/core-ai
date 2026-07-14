package ai.core.server.skill;

import ai.core.server.domain.SkillDefinition;
import ai.core.server.domain.SkillRepoConfig;
import ai.core.server.domain.SkillResource;
import ai.core.server.domain.SkillSourceType;
import ai.core.skill.SkillMetadata;
import com.mongodb.client.model.Filters;
import core.framework.mongo.MongoCollection;
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
class SkillRepoManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(SkillRepoManager.class);
    private static final Pattern REPO_OWNER_PATTERN = Pattern.compile("https?://[^/]+/([^/]+)/");

    private final MongoCollection<SkillDefinition> skillCollection;

    SkillRepoManager(MongoCollection<SkillDefinition> skillCollection) {
        this.skillCollection = skillCollection;
    }

    SkillDefinition registerOrUpdate(String userId, String namespace, SkillMetadata skill, String repoUrl, String branch, String skillPath) {
        String qualifiedName = namespace + "/" + skill.getName();
        Path skillDir = skill.getSkillDir() != null
            ? Path.of(skill.getSkillDir())
            : Path.of(skill.getPath()).getParent();

        if (skillDir == null) {
            throw new RuntimeException("cannot determine skill directory for " + skill.getName() + ", path=" + skill.getPath());
        }

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

    String readSkillMdFromDir(Path skillDir) {
        try {
            return Files.readString(skillDir.resolve("SKILL.md"), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("failed to read SKILL.md from " + skillDir, e);
        }
    }

    List<SkillResource> readResourcesFromDir(Path skillDir, List<String> paths) {
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

    void cloneRepo(String repoUrl, String branch, Path targetDir) throws IOException {
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
    void deleteTempDir(Path dir) {
        if (dir == null) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                .forEach(path -> path.toFile().delete());
        } catch (IOException e) {
            LOGGER.warn("failed to delete temp dir: {}", dir, e);
        }
    }
}
