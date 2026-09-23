package ai.core.server.skill;

import ai.core.server.domain.SkillDefinition;
import core.framework.mongo.MongoCollection;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

class SkillRepoManagerTest {
    private static final String PACK_CONTENT = "packed objects";
    private static final FileTime ABANDONED = FileTime.from(Instant.now().minus(3, ChronoUnit.HOURS));

    private static Boolean gitAvailable;

    @BeforeAll
    static void probeGit() {
        gitAvailable = run(null, List.of("git", "--version")) == 0;
    }

    private static void git(Path workDir, String... args) {
        var command = new ArrayList<String>();
        command.add("git");
        command.addAll(List.of(args));
        assertEquals(0, run(workDir, command), "command failed: " + command);
    }

    private static int run(Path workDir, List<String> command) {
        try {
            var builder = new ProcessBuilder(command).redirectErrorStream(true);
            if (workDir != null) builder.directory(workDir.toFile());
            var process = builder.start();
            new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return process.waitFor();
        } catch (IOException | InterruptedException e) {
            return -1;
        }
    }

    @TempDir
    Path tempDir;

    @Test
    void deleteTempDirRemovesReadOnlyGitPackFiles() throws IOException {
        var manager = manager();
        gitPack(tempDir.resolve("skill-repo-sync-1"), ABANDONED);

        long freed = manager.deleteTempDir(tempDir.resolve("skill-repo-sync-1"));

        assertEquals(PACK_CONTENT.length(), freed);
        assertFalse(Files.exists(tempDir.resolve("skill-repo-sync-1")));
    }

    @Test
    void sweepRemovesAbandonedClonesAndKeepsFreshOrForeignDirs() throws IOException {
        var manager = manager();
        gitPack(tempDir.resolve("skill-repo-sync-111"), ABANDONED);
        gitPack(tempDir.resolve("skill-repo-222"), ABANDONED);
        Files.createDirectories(tempDir.resolve("skill-repo-sync-333"));
        Files.createDirectories(tempDir.resolve("other-app"));

        long freed = manager.sweepStaleTempDirs(tempDir);

        assertEquals(PACK_CONTENT.length() * 2L, freed);
        assertFalse(Files.exists(tempDir.resolve("skill-repo-sync-111")));
        assertFalse(Files.exists(tempDir.resolve("skill-repo-222")));
        assertTrue(Files.exists(tempDir.resolve("skill-repo-sync-333")));
        assertTrue(Files.exists(tempDir.resolve("other-app")));
    }

    @Test
    void sweepOfMissingTempRootFreesNothing() {
        assertEquals(0L, manager().sweepStaleTempDirs(tempDir.resolve("missing")));
    }

    @Test
    void cloneAndRemoteHeadFollowTheLocalRemote() throws IOException {
        assumeTrue(gitAvailable, "git is not installed");
        var manager = manager();
        var origin = Files.createDirectories(tempDir.resolve("origin"));
        git(origin, "init", "-b", "main", ".");
        git(origin, "config", "user.email", "test@example.com");
        git(origin, "config", "user.name", "test");
        Files.writeString(origin.resolve("SKILL.md"), "---\nname: demo\ndescription: demo skill\n---\n");
        git(origin, "add", ".");
        git(origin, "commit", "-m", "skill");

        var head = manager.remoteHead(origin.toString(), "main");
        var clone = Files.createTempDirectory(tempDir, "clone-");
        manager.cloneRepo(origin.toString(), "main", clone);

        assertTrue(head != null && head.length() == 40, "remote head: " + head);
        assertEquals(head, manager.headCommit(clone));
        assertTrue(Files.exists(clone.resolve("SKILL.md")));
    }

    private SkillRepoManager manager() {
        return new SkillRepoManager(skillCollection());
    }

    private void gitPack(Path dir, FileTime lastModified) throws IOException {
        var pack = Files.createDirectories(dir.resolve(".git/objects/pack")).resolve("pack-abc123.pack");
        Files.writeString(pack, PACK_CONTENT);
        try (var walk = Files.walk(dir)) {
            for (var path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.setLastModifiedTime(path, lastModified);
            }
        }
        pack.toFile().setWritable(false);
    }

    @SuppressWarnings("unchecked")
    private MongoCollection<SkillDefinition> skillCollection() {
        return (MongoCollection<SkillDefinition>) mock(MongoCollection.class);
    }
}
