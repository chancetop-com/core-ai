package ai.core.server.trigger.action;

/**
 * @author stephen
 */
public class TriggerActionResult {
    public static TriggerActionResult running(String runId) {
        var result = new TriggerActionResult();
        result.runId = runId;
        result.status = "RUNNING";
        return result;
    }

    public static TriggerActionResult skipped(String reason) {
        var result = new TriggerActionResult();
        result.runId = null;
        result.status = "SKIPPED";
        return result;
    }

    public String runId;
    public String status;
}
