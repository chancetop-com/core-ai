package ai.core.server.asynctask;

/**
 * Hands a finished long-running tool call to the session that issued it. Implemented over the session
 * command bus, so the notification lands on whichever replica owns the session — and rebuilds it when
 * it is no longer in memory — instead of only reaching a session that happens to be live in this JVM.
 *
 * @author stephen
 */
public interface TaskNotificationDispatcher {
    /** What happened to a notification handed to the bus; the caller turns this into retry bookkeeping. */
    enum Outcome {
        /** Accepted for delivery. The receiving side confirms by claiming the task's delivery flag. */
        DISPATCHED,
        /** Not delivered this time (bus down, owner unreachable); the sweep will try again. */
        RETRY,
        /** There is no session left to deliver to; stop trying. */
        ABANDONED
    }

    Outcome dispatch(String sessionId, String taskId, String toolName, String status, String notificationXml);
}
