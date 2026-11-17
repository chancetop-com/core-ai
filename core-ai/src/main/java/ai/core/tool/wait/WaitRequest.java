package ai.core.tool.wait;

/**
 * Typed request produced by a {@link RuntimeWaitTool}: "do not return until this task stops".
 * A non-positive timeout means no independent wait deadline; the turn's cancellation token and
 * the run budget still bound the wait.
 *
 * @author stephen
 */
public record WaitRequest(String taskId, long timeoutMs) {
    public static WaitRequest of(String taskId, long timeoutMs) {
        return new WaitRequest(taskId, timeoutMs);
    }
}
