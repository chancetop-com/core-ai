package ai.core.server.trigger.action;

/**
 * @author stephen
 */
public class TriggerActionResult {
    public String runId;
    public String status;

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
}
