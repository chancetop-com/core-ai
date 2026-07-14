package ai.core.tool.tools;

import ai.core.agent.ExecutionContext;
import ai.core.api.tool.function.CoreAiMethod;
import ai.core.api.tool.function.CoreAiParameter;
import ai.core.tool.ToolCall;
import ai.core.tool.function.Functions;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Task V2 tool — incremental CRUD task management with file-based persistence.
 * Replaces the V1 {@link WriteTodosTool} (full-replace model) with id-based
 * create/update/list/get operations and dependency tracking.
 *
 * @author lim chen
 */
public class WriteTodoTaskTool {

    public static final String PLAN_GROUP_C = "PlanCreate";
    public static final String PLAN_GROUP_U = "PlanUpdate";
    public static final String TOOL_NAME_CREATE = "task_create";
    public static final String TOOL_NAME_UPDATE = "task_update";
    public static final String TOOL_NAME_LIST = "task_list";
    public static final String TOOL_NAME_GET = "task_get";

    private static final String TASK_CREATE_DESC = """
            Use this tool to create a structured task list for your current coding session. This helps you track progress, organize complex tasks, and demonstrate thoroughness to the user.
            It also helps the user understand the progress of the task and overall progress of their requests.
            
            ## When to Use This Tool
            
            Use this tool proactively in these scenarios:
            
            - Complex multi-step tasks - When a task requires 3 or more distinct steps or actions
            - Non-trivial and complex tasks - Tasks that require careful planning or multiple operations
            - Plan mode - When using plan mode, create a task list to track the work
            - User explicitly requests todo list - When the user directly asks you to use the todo list
            - User provides multiple tasks - When users provide a list of things to be done (numbered or comma-separated)
            - After receiving new instructions - Immediately capture user requirements as tasks
            - When you start working on a task - Mark it as in_progress BEFORE beginning work
            - After completing a task - Mark it as completed and add any new follow-up tasks discovered during implementation
            
            ## When NOT to Use This Tool
            
            Skip using this tool when:
            - There is only a single, straightforward task
            - The task is trivial and tracking it provides no organizational benefit
            - The task can be completed in less than 3 trivial steps
            - The task is purely conversational or informational
            
            NOTE that you should not use this tool if there is only one trivial task to do.
            
            ## Task Fields
            - **subject**: Brief, imperative-form title (e.g., "Fix authentication bug")
            - **description**: What needs to be done
            - **activeForm** (optional): Present continuous form for spinner (e.g., "Fixing authentication bug")
            
            Tasks are created with status `pending`. Check task_list first to avoid duplicates.
            After creating tasks, use task_update to set up dependencies (blocks/blockedBy) if needed.
            
            """;

    private static final String TASK_UPDATE_DESC = """
            Use this tool to update a task in the task list.
            
            ## When to Use This Tool
            
            **Mark tasks as resolved:**
            - When you have completed the work described in a task
            - When a task is no longer needed or has been superseded
            - IMPORTANT: Always mark your assigned tasks as resolved when you finish them
            - After resolving, call TaskList to find your next task
            
            - ONLY mark a task as completed when you have FULLY accomplished it
            - If you encounter errors, blockers, or cannot finish, keep the task as in_progress
            - When blocked, create a new task describing what needs to be resolved
            - Never mark a task as completed if:
              - Tests are failing
              - Implementation is partial
              - You encountered unresolved errors
              - You couldn't find necessary files or dependencies
            
            **Delete tasks:**
            - When a task is no longer relevant or was created in error
            - Setting status to `deleted` permanently removes the task
            
            **Update task details:**
            - When requirements change or become clearer
            - When establishing dependencies between tasks
            
            ## Fields You Can Update
            
            - **status**: The task status (see Status Workflow below)
            - **subject**: Change the task title (imperative form, e.g., "Run tests")
            - **description**: Change the task description
            - **activeForm**: Present continuous form shown in spinner when in_progress (e.g., "Running tests")
            - **owner**: Change the task owner (agent name)
            - **metadata**: Merge metadata keys into the task (set a key to null to delete it)
            - **addBlocks**: Mark tasks that cannot start until this one completes
            - **addBlockedBy**: Mark tasks that must complete before this one can start
            
            ## Status Workflow
            
            Status progresses: `pending` → `in_progress` → `completed`
            
            Use `deleted` to permanently remove a task.
            
            ## Staleness
            
            Make sure to read a task's latest state using `task_get` before updating it.
            
            ## Examples
            - Mark in_progress: `{"taskId": "1", "status": "in_progress"}`
            - Mark completed: `{"taskId": "1", "status": "completed"}`
            - Delete: `{"taskId": "1", "status": "deleted"}`
            - Set owner: `{"taskId": "1", "owner": "my-name"}`
            - Set dependency: `{"taskId": "2", "addBlockedBy": ["1"]}`
            
            """;

    private static final String TASK_LIST_DESC = """
            Use this tool to list all tasks in the task list.
            
            ## When to Use This Tool
            
            - To see what tasks are available to work on (status: 'pending', no owner, not blocked)
            - To check overall progress on the project
            - To find tasks that are blocked and need dependencies resolved
            - After completing a task, to check for newly unblocked work or claim the next available task
            - **Prefer working on tasks in ID order** (lowest ID first) when multiple tasks are available, as earlier tasks often set up context for later ones
            
            ## Output
            
            Returns a summary of each task:
            - **id**: Task identifier (use with TaskGet, TaskUpdate)
            - **subject**: Brief description of the task
            - **status**: 'pending', 'in_progress', or 'completed'
            - **owner**: Agent ID if assigned, empty if available
            - **blockedBy**: List of open task IDs that must be resolved first (tasks with blockedBy cannot be claimed until dependencies resolve)
            
            Use task_get with a specific task ID to view full details including description and comments.
            
            """;

    private static final String TASK_GET_DESC = """
            Use this tool to retrieve a task by its ID from the task list.
            
            ## When to Use This Tool
            
            - When you need the full description and context before starting work on a task
            - To understand task dependencies (what it blocks, what blocks it)
            - After being assigned a task, to get complete requirements
            
            ## Output
            
            Returns full task details:
            - **subject**: Task title
            - **description**: Detailed requirements and context
            - **status**: 'pending', 'in_progress', or 'completed'
            - **blocks**: Tasks waiting on this one to complete
            - **blockedBy**: Tasks that must complete before this one can start
            
            ## Tips
            
            - After fetching a task, verify its blockedBy list is empty before beginning work.
            - Use task_list to see all tasks in summary form.
            
            """;

    static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static List<ToolCall> self() {
        return (List) Functions.from(new WriteTodoTaskTool());
    }

    @CoreAiMethod(name = TOOL_NAME_CREATE, description = TASK_CREATE_DESC, concurrencyGroup = PLAN_GROUP_C)
    public TaskEntity createTask(CreateTaskParams params, ExecutionContext context) {
        var store = context.getTodoStoreFactory().create(context.getSessionId());
        var task = new TaskEntity();
        task.subject = params.subject;
        task.description = params.description;
        task.activeForm = params.activeForm;
        task.status = "pending";
        task.blocks = new ArrayList<>();
        task.blockedBy = new ArrayList<>();
        task.metadata = params.metadata;

        return store.create(task);
    }

    @CoreAiMethod(name = TOOL_NAME_UPDATE, description = TASK_UPDATE_DESC, concurrencyGroup = PLAN_GROUP_U)
    public String updateTask(UpdateTaskParams params, ExecutionContext context) {
        var store = context.getTodoStoreFactory().create(context.getSessionId());
        var task = store.read(params.taskId);
        if (task == null) {
            return errorJson("task #%s not found".formatted(params.taskId));
        }

        if ("deleted".equals(params.status)) {
            store.delete(params.taskId);
            return "<system-reminder>%nTask #%s has been deleted.%n</system-reminder>%n".formatted(params.taskId);
        }

        boolean changed = applyFieldUpdates(params, task);
        applyDependencyUpdates(params, store);
        if (changed) {
            store.write(params.taskId, task);
        }

        var reminder = new StringBuilder();
        if ("completed".equals(task.status)) {
            reminder.append("Task #%s completed. Call task_list to find your next available task.%n".formatted(params.taskId));
            checkVerificationNudge(store, reminder);
        }
        return "<system-reminder>%nTask #%s updated. Status: %s.%n%s</system-reminder>%n".formatted(params.taskId, task.status, reminder.toString());
    }

    @CoreAiMethod(name = TOOL_NAME_LIST, description = TASK_LIST_DESC)
    public String listTasks(ExecutionContext context) {
        var store = context.getTodoStoreFactory().create(context.getSessionId());
        List<TaskEntity> all = store.listAll().stream()
                .filter(t -> t.metadata == null || !Boolean.TRUE.equals(t.metadata.get("_internal")))
                .toList();
        if (all.isEmpty()) {
            return "{\"tasks\": []}\n<system-reminder>No tasks found. Use task_create to add tasks.</system-reminder>";
        }
        Set<String> completed = all.stream()
                .filter(t -> "completed".equals(t.status))
                .map(t -> t.id)
                .collect(Collectors.toSet());

        var summaries = new ArrayList<String>();
        for (var task : all) {
            List<String> activeBlockers = task.blockedBy != null
                    ? task.blockedBy.stream().filter(id -> !completed.contains(id)).toList()
                    : List.of();
            summaries.add(summaryJson(task, activeBlockers));
        }

        return "{\"tasks\": [%s]}%n".formatted(String.join(", ", summaries));
    }

    @CoreAiMethod(name = TOOL_NAME_GET, description = TASK_GET_DESC)
    public TaskEntity getTask(GetTaskParams params, ExecutionContext context) {
        var store = context.getTodoStoreFactory().create(context.getSessionId());
        return store.read(params.taskId);
    }

    private boolean applyFieldUpdates(UpdateTaskParams params, TaskEntity task) {
        boolean changed = false;
        if (params.status != null && !params.status.equals(task.status)) {
            task.status = params.status;
            changed = true;
        }
        if (params.subject != null) {
            task.subject = params.subject;
            changed = true;
        }
        if (params.description != null) {
            task.description = params.description;
            changed = true;
        }
        if (params.activeForm != null) {
            task.activeForm = params.activeForm;
            changed = true;
        }
        if (params.owner != null) {
            task.owner = params.owner;
            changed = true;
        }
        if (params.metadata != null) {
            applyMetadataUpdate(params.metadata, task);
            changed = true;
        }
        return changed;
    }

    private void applyMetadataUpdate(Map<String, Object> updates, TaskEntity task) {
        if (task.metadata == null) task.metadata = new java.util.LinkedHashMap<>();
        for (var entry : updates.entrySet()) {
            if (entry.getValue() == null) {
                task.metadata.remove(entry.getKey());
            } else {
                task.metadata.put(entry.getKey(), entry.getValue());
            }
        }
    }

    private void applyDependencyUpdates(UpdateTaskParams params, TodoStore store) {
        if (params.addBlocks != null) {
            for (String blockedId : params.addBlocks) {
                if (!blockedId.equals(params.taskId)) {
                    addBlockRelation(store, params.taskId, blockedId);
                }
            }
        }
        if (params.addBlockedBy != null) {
            for (String blockerId : params.addBlockedBy) {
                if (!blockerId.equals(params.taskId)) {
                    addBlockRelation(store, blockerId, params.taskId);
                }
            }
        }
    }

    private void addBlockRelation(TodoStore store, String blockerId, String blockedId) {
        var blocker = store.read(blockerId);
        var blocked = store.read(blockedId);
        if (blocker == null || blocked == null) return;

        if (blocker.blocks == null) blocker.blocks = new ArrayList<>();
        if (blocked.blockedBy == null) blocked.blockedBy = new ArrayList<>();

        if (!blocker.blocks.contains(blockedId)) {
            blocker.blocks.add(blockedId);
            store.write(blockerId, blocker);
        }
        if (!blocked.blockedBy.contains(blockerId)) {
            blocked.blockedBy.add(blockerId);
            store.write(blockedId, blocked);
        }
    }

    private void checkVerificationNudge(TodoStore store, StringBuilder reminder) {
        List<TaskEntity> all = store.listAll();
        long incomplete = all.stream().filter(t -> !"completed".equals(t.status)).count();
        if (incomplete == 0 && all.size() >= 3
                && all.stream().noneMatch(t -> t.subject != null && t.subject.toLowerCase(Locale.ENGLISH).contains("verif"))) {
            reminder.append("All tasks completed. Consider a verification step (e.g., test the changes end-to-end).\n");
        }
    }

    private String summaryJson(TaskEntity task, List<String> activeBlockers) {
        var sb = new StringBuilder(128);
        sb.append("{\"id\":\"").append(escapeJson(task.id)).append("\",\"subject\":\"")
          .append(escapeJson(task.subject)).append("\",\"status\":\"")
          .append(escapeJson(task.status)).append('"');
        if (task.owner != null && !task.owner.isEmpty()) {
            sb.append(",\"owner\":\"").append(escapeJson(task.owner)).append('"');
        }
        if (!activeBlockers.isEmpty()) {
            sb.append(",\"blockedBy\":[")
                .append(activeBlockers.stream().map(id -> '"' + id + '"').collect(Collectors.joining(",")))
                .append(']');
        }
        sb.append('}');
        return sb.toString();
    }

    private String errorJson(String message) {
        return "{\"error\": \"" + escapeJson(message) + "\"}";
    }

    // ---- parameter types ----

    public static class CreateTaskParams {
        @CoreAiParameter(name = "subject", description = "A brief title for the task")
        public String subject;

        @CoreAiParameter(name = "description", description = "What needs to be done")
        public String description;

        @CoreAiParameter(name = "activeForm", description = "Present continuous form shown in spinner when in_progress (e.g., \\\"Running tests\\\")", required = false)
        public String activeForm;

        @CoreAiParameter(name = "metadata", description = "Arbitrary metadata to attach to the task", required = false)
        public Map<String, Object> metadata;
    }

    public static class UpdateTaskParams {
        @CoreAiParameter(name = "taskId", description = "The ID of the task to update")
        public String taskId;

        @CoreAiParameter(name = "subject", description = "New subject for the task", required = false)
        public String subject;

        @CoreAiParameter(name = "description", description = "New description for the task", required = false)
        public String description;

        @CoreAiParameter(name = "activeForm", description = "Present continuous form shown in spinner when in_progress (e.g., \\\"Running tests\\\")", required = false)
        public String activeForm;

        @CoreAiParameter(name = "status", description = "New status for the task", required = false, enums = {"pending", "in_progress", "completed"})
        public String status;

        @CoreAiParameter(name = "addBlocks", description = "Task IDs that this task blocks", required = false)
        public List<String> addBlocks;

        @CoreAiParameter(name = "addBlockedBy", description = "Task IDs that block this task", required = false)
        public List<String> addBlockedBy;

        @CoreAiParameter(name = "owner", description = "New owner for the task", required = false)
        public String owner;

        @CoreAiParameter(name = "metadata", description = "Metadata keys to merge into the task. Set a key to null to delete it.", required = false)
        public Map<String, Object> metadata;
    }

    public static class GetTaskParams {
        @CoreAiParameter(name = "taskId", description = "The ID of the task to retrieve")
        public String taskId;
    }

    public static class TaskEntity {
        public String id;
        public String subject;
        public String description;
        public String activeForm;
        public String owner;
        public String status;
        public List<String> blocks;
        public List<String> blockedBy;
        public Map<String, Object> metadata;
    }
}
