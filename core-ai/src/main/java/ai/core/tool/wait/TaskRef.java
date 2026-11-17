package ai.core.tool.wait;

/**
 * Stable identity of one async task generation. A bare task id is not unique: tools that key a
 * task per subject reuse the id, and the same id in another sandbox or session is another task.
 *
 * @param ownerScope   the session/execution scope allowed to observe this task
 * @param backend      task source family ("background", "executor", "managed")
 * @param backendOwner the concrete backend instance (a sandbox id, when the source needs one)
 * @param taskId       the id exposed to the model
 * @param generation   monotonically increasing per task id; late signals of an old generation must not wake a new task
 * @author stephen
 */
public record TaskRef(String ownerScope, String backend, String backendOwner, String taskId, long generation) {
    public String key() {
        return taskId + "#" + generation;
    }

    public boolean visibleTo(String scope) {
        return ownerScope == null || ownerScope.equals(scope);
    }
}
