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

    public static void cleanup(Sandbox sandbox) {
        if (sandbox == null || retentionDays <= 0) return;
        var command = "find " + DIR + " -type f -name '*.out' -mtime +" + retentionDays
                + " -delete 2>/dev/null || true";
        try {
            sandbox.execute("run_bash_command", JSON.toJSON(Map.of("command", command)), null);
        } catch (RuntimeException e) {
            LOGGER.warn("failed to prune workspace task outputs, dir={}", DIR, e);
        }
    }

    private WorkspaceTaskOutputs() {
    }
}
