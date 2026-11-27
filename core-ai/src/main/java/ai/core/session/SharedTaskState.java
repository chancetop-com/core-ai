package ai.core.session;

import ai.core.session.lifecycle.TaskRunCoordinator;
import ai.core.tool.wait.TaskCompletionRegistry;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * State shared between a manager and its children, so every generation sees the same terminal
 * results, consumption tombstones and task-run coordination.
 *
 * @author stephen
 */
record SharedTaskState(Map<String, TerminalTask> terminalTasks, ConsumedTasks consumedTasks,
                       TaskCompletionRegistry completions, TaskRunCoordinator taskRuns,
                       AtomicInteger activeTaskCount) {
}
