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
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Clones repo skills into per-run temp dirs. Git marks its packed objects read-only, which defeats plain
 * {@code File.delete()} on Windows, so temp clones are force-deleted and abandoned ones are swept by age.
 * Git invocations are bounded in time and never prompt for credentials, so a dead network cannot leave a
 * hung clone (or its multi-hundred-MB pack) behind.
 *
 * @author stephen
 */
class SkillRepoManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(SkillRepoManager.class);
    private static final Pattern REPO_OWNER_PATTERN = Pattern.compile("https?://[^/]+/([^/]+)/");
    private static final Pattern PEELED_REF_PATTERN = Pattern.compile("\\s\\^\\{\\}\\s*$");
    private static final Duration GIT_TIMEOUT = Duration.ofMinutes(15);
    private static final Duration STALE_TEMP_DIR_AGE = Duration.ofHours(1);

    static final String TEMP_DIR_PREFIX = "skill-repo-";
    static final String SYNC_TEMP_DIR_PREFIX = TEMP_DIR_PREFIX + "sync-";

    private static String effectiveBranch(String branch) {
        return branch == null || branch.isBlank() ? "main" : branch.trim();
    }

    private static String output(Process process) throws IOException {
        return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static String firstHash(String lsRemoteOutput) {
        return lsRemoteOutput.lines()
            .filter(line -> !PEELED_REF_PATTERN.matcher(line).find())
            .map(SkillRepoManager::hashOf)
            .filter(Objects::nonNull)
            .findFirst()
            .orElse(null);
    }

    private static String hashOf(String lsRemoteLine) {
        var separator = lsRemoteLine.indexOf('\t');
        return separator <= 0 ? null : lsRemoteLine.substring(0, separator).trim();
    }

    private static long deleteReadOnlyAware(Path path) {
        try {
            var size = Files.isRegularFile(path) ? Files.size(path) : 0L;
            path.toFile().setWritable(true);
            return Files.deleteIfExists(path) ? size : 0L;
        } catch (IOException e) {
            LOGGER.warn("failed to delete skill repo temp path, path={}", path, e);
            return 0L;
        }
    }

    private static boolean isRecent(Path dir) {
        var cutoff = Instant.now().minus(STALE_TEMP_DIR_AGE);
        try (var walk = Files.walk(dir)) {
            return walk.anyMatch(path -> lastModified(path).isAfter(cutoff));
        } catch (IOException e) {
            return true;
        }
    }

    private static Instant lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toInstant();
        } catch (IOException e) {
            return Instant.now();
        }
    }

    private final MongoCollection<SkillDefinition> skillCollection;

    SkillRepoManager(MongoCollection<SkillDefinition> skillCollection) {
        this.skillCollection = skillCollection;
    }

    SkillDefinition registerOrUpdate(String userId, String namespace, SkillMetadata skill, SkillRepoSource source) {
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
        entity.digest = SkillDigest.of(entity.content, entity.resources);
        entity.updatedAt = ZonedDateTime.now();

        var repoConfig = new SkillRepoConfig();
        repoConfig.repoUrl = source.url();
        repoConfig.branch = effectiveBranch(source.branch());
        repoConfig.skillPath = source.skillPath();
        repoConfig.lastSyncedAt = ZonedDateTime.now();
        repoConfig.lastCommitHash = source.commitHash();
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
        runGit(List.of("clone", "--depth", "1", "--branch", effectiveBranch(branch), repoUrl, targetDir.toString()), null);
    }

    /** The tip of the branch on the remote, null when it cannot be read (unreachable, unknown branch) or is empty. */
    String remoteHead(String repoUrl, String branch) {
        var name = effectiveBranch(branch);
        try {
            var head = firstHash(runGit(List.of("ls-remote", repoUrl, "refs/heads/" + name), null));
            return head != null ? head : firstHash(runGit(List.of("ls-remote", repoUrl, name), null));
        } catch (IOException e) {
            LOGGER.warn("cannot read remote head, repo={}, branch={}, error={}", repoUrl, name, e.getMessage());
            return null;
        }
    }

    /** The commit a clone ended up on, null when the work tree has no commit. */
    String headCommit(Path repoDir) {
        try {
            return runGit(List.of("rev-parse", "HEAD"), repoDir).trim();
        } catch (IOException e) {
            LOGGER.warn("cannot read cloned commit, path={}, error={}", repoDir, e.getMessage());
            return null;
        }
    }

    /** Deletes the temp clone, returning the bytes actually freed; read-only git packs would otherwise survive. */
    long deleteTempDir(Path dir) {
        if (dir == null || !Files.exists(dir)) return 0L;
        long freed = 0L;
        try (var walk = Files.walk(dir)) {
            for (var path : walk.sorted(Comparator.reverseOrder()).toList()) {
                freed += deleteReadOnlyAware(path);
            }
        } catch (IOException e) {
            LOGGER.warn("failed to walk skill repo temp dir, path={}", dir, e);
        }
        if (Files.exists(dir)) {
            LOGGER.warn("skill repo temp dir survived cleanup, path={}", dir);
        }
        return freed;
    }

    /** Removes temp clones abandoned by a killed or crashed server, so their packs cannot pile up. */
    long sweepStaleTempDirs() {
        return sweepStaleTempDirs(Path.of(System.getProperty("java.io.tmpdir")));
    }

    long sweepStaleTempDirs(Path tempRoot) {
        if (!Files.isDirectory(tempRoot)) return 0L;
        long freed = 0L;
        int removed = 0;
        try (var entries = Files.list(tempRoot)) {
            for (var entry : entries.toList()) {
                var name = entry.getFileName();
                if (name == null || !Files.isDirectory(entry) || !name.toString().startsWith(TEMP_DIR_PREFIX) || isRecent(entry)) continue;
                long bytes = deleteTempDir(entry);
                if (Files.exists(entry)) continue;
                freed += bytes;
                removed++;
            }
        } catch (IOException e) {
            LOGGER.warn("failed to list temp dir, path={}", tempRoot, e);
        }
        if (removed > 0) {
            LOGGER.info("removed stale skill repo temp dir(s), count={}, freedMB={}", removed, freed / (1024 * 1024));
        }
        return freed;
    }

    private String runGit(List<String> args, Path workDir) throws IOException {
        var command = new ArrayList<String>(args.size() + 1);
        command.add("git");
        command.addAll(args);
        var builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        if (workDir != null) builder.directory(workDir.toFile());
        var process = builder.start();
        try {
            if (!process.waitFor(GIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IOException("git " + args.getFirst() + " timed out after " + GIT_TIMEOUT.toMinutes() + "m: " + output(process));
            }
            if (process.exitValue() != 0) {
                throw new IOException("git " + args.getFirst() + " failed (exit=" + process.exitValue() + "): " + output(process));
            }
            return output(process);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("git " + args.getFirst() + " interrupted", e);
        }
    }
}
