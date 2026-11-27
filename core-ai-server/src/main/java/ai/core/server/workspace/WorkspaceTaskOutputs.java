package ai.core.server.workspace;

import ai.core.sandbox.Sandbox;
import core.framework.json.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Retention of the server background-task output files that live on the workspace volume. The files
 * are written by {@code SandboxSubagentOutputSinkFactory}; this class prunes them on the next runtime
 * attach so a workspace that is never used again keeps its files until its next wake.
 *
 * @author stephen
 */
public final class WorkspaceTaskOutputs {
    public static final String DIR = "/workspace/.core-ai/tasks";
    private static final Logger LOGGER = LoggerFactory.getLogger(WorkspaceTaskOutputs.class);
    private static volatile int retentionDays = 7;

    public static void retentionDays(int days) {
        retentionDays = Math.max(0, days);
    }

    static int retentionDays() {
        return retentionDays;
    }

    public static int cleanup(Sandbox sandbox) {
        if (sandbox == null || retentionDays <= 0) return 0;
        var retentionMinutes = retentionDays * 24 * 60;
        var command = "if [ -d " + DIR + " ]; then find " + DIR
                + " -maxdepth 1 -type f -name '*.out' -mmin +" + retentionMinutes
                + " -print -delete 2>/dev/null | wc -l; else echo 0; fi";
        try {
            var result = sandbox.execute("run_bash_command", JSON.toJSON(Map.of("command", command)), null);
            var deleted = parseCount(result != null ? result.getResult() : null);
            LOGGER.info("pruned workspace task outputs, dir={}, retentionDays={}, deleted={}", DIR, retentionDays, deleted);
            return deleted;
        } catch (RuntimeException e) {
            LOGGER.warn("failed to prune workspace task outputs, dir={}", DIR, e);
            return 0;
        }
    }

    private static int parseCount(String output) {
        if (output == null || output.isBlank()) return 0;
        var lines = output.trim().split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            var line = lines[i].trim();
            if (!line.isEmpty() && line.chars().allMatch(Character::isDigit)) {
                return Integer.parseInt(line);
            }
        }
        return 0;
    }

    private WorkspaceTaskOutputs() {
    }
}
