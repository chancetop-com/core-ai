package ai.core.task;

import ai.core.persistence.Persistence;

/**
 * @author stephen
 */
public class TaskPersistence implements Persistence<Task> {
    @Override
    public String serialization(Task task) {
        return "";
    }

    @Override
    public void deserialization(Task task, String c) {

    }
}
