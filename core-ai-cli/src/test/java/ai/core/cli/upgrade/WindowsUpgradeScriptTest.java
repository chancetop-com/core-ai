package ai.core.cli.upgrade;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the generated core-ai-upgrade.ps1 against a stand-in binary: a copy of cmd.exe, which holds
 * its own image locked while it runs and therefore forces the script into the rename-aside path.
 *
 * @author stephen
 */
@EnabledOnOs(OS.WINDOWS)
class WindowsUpgradeScriptTest {

    private static final String BINARY_NAME = "core-ai-cli.exe";
    private static final String SCRIPT_NAME = "core-ai-upgrade.ps1";
    private static final String NEW_CONTENT = "new-version";

    @TempDir
    Path tempDir;

    private final List<Process> started = new ArrayList<>();

    @AfterEach
    void stopStartedProcesses() {
        for (Process process : started) {
            kill(process);
        }
    }

    @Test
    void replacesBinaryAfterCliExits() throws Exception {
        Path binary = fakeCli();
        Path newFile = pendingFile(binary);
        Process cli = startFakeCli(binary, 4);

        int exitCode = runUpgradeScript(binary, newFile, cli.pid());

        assertEquals(0, exitCode);
        assertEquals(NEW_CONTENT, Files.readString(binary));
        assertFalse(Files.exists(newFile));
        assertFalse(Files.exists(scriptFile(binary)));
        assertFalse(Files.exists(backupFile(binary)));
    }

    @Test
    void replacesBinaryWhileAnotherInstanceKeepsRunning() throws Exception {
        Path binary = fakeCli();
        long originalSize = Files.size(binary);
        Path newFile = pendingFile(binary);
        Process other = startFakeCli(binary, 120);
        Process cli = startFakeCli(binary, 4);

        int exitCode = runUpgradeScript(binary, newFile, cli.pid());

        assertEquals(0, exitCode);
        assertEquals(NEW_CONTENT, Files.readString(binary));
        assertTrue(Files.exists(backupFile(binary)), "the locked binary should have been renamed aside");
        assertEquals(originalSize, Files.size(backupFile(binary)));

        kill(other);
        awaitBackupRemoval(binary);
    }

    @Test
    void exitsWithoutTouchingAnythingWhenNoUpgradeIsPending() throws Exception {
        Path binary = fakeCli();
        long originalSize = Files.size(binary);
        Path newFile = binary.resolveSibling(BINARY_NAME + ".new");

        int exitCode = runUpgradeScript(binary, newFile, 0);

        assertEquals(0, exitCode);
        assertEquals(originalSize, Files.size(binary));
    }

    private Path fakeCli() throws Exception {
        Path binary = tempDir.resolve(BINARY_NAME);
        Files.copy(Path.of(System.getenv("ComSpec")), binary, StandardCopyOption.REPLACE_EXISTING);
        return binary;
    }

    private Path pendingFile(Path binary) throws Exception {
        Path newFile = binary.resolveSibling(BINARY_NAME + ".new");
        Files.writeString(newFile, NEW_CONTENT);
        return newFile;
    }

    private Path scriptFile(Path binary) {
        return binary.resolveSibling(SCRIPT_NAME);
    }

    private Path backupFile(Path binary) {
        return binary.resolveSibling(BINARY_NAME + ".old");
    }

    private Process startFakeCli(Path binary, int seconds) throws Exception {
        Process process = new ProcessBuilder(binary.toString(), "/c", "ping -n " + seconds + " 127.0.0.1")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        started.add(process);
        return process;
    }

    private int runUpgradeScript(Path binary, Path newFile, long cliPid) throws Exception {
        Path script = scriptFile(binary);
        Files.writeString(script, UpgradeDownloader.buildWindowsUpgradeScript(newFile, binary, script, cliPid));
        Process process = new ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        started.add(process);
        assertTrue(process.waitFor(90, TimeUnit.SECONDS), "upgrade script did not finish");
        return process.exitValue();
    }

    private void awaitBackupRemoval(Path binary) throws Exception {
        for (int i = 0; i < 100 && Files.exists(backupFile(binary)); i++) {
            UpgradeDownloader.cleanupReplacedBinaries(binary);
            Thread.sleep(100);
        }
        assertFalse(Files.exists(backupFile(binary)), "replaced binary was not cleaned up");
    }

    private void kill(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        try {
            process.waitFor(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
