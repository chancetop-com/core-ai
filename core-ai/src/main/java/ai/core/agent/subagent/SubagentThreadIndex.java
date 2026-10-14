package ai.core.agent.subagent;

import core.framework.api.json.Property;

import java.util.ArrayList;
import java.util.List;

/**
 * Dormant view of a session's subagent threads, stored under one index key so a restored session can
 * list its threads without deserializing any of them — a thread is only rebuilt when it is continued.
 *
 * @author stephen
 */
public class SubagentThreadIndex {
    public static SubagentThreadIndex of(List<SubagentThread> threads) {
        var index = new SubagentThreadIndex();
        index.threads = new ArrayList<>(threads.size());
        for (var thread : threads) {
            index.threads.add(ThreadRef.of(thread));
        }
        return index;
    }

    @Property(name = "threads")
    public List<ThreadRef> threads;

    public static class ThreadRef {
        static ThreadRef of(SubagentThread thread) {
            var ref = new ThreadRef();
            ref.taskId = thread.taskId();
            ref.kind = thread.kind();
            ref.type = thread.type();
            ref.description = thread.description();
            ref.model = thread.model();
            ref.runCount = thread.runCount();
            ref.createdAtMs = thread.createdAtMs();
            ref.lastRunAtMs = thread.lastRunAtMs();
            ref.contextTokens = thread.contextTokens();
            ref.lastSummary = thread.lastSummary();
            return ref;
        }

        @Property(name = "task_id")
        public String taskId;

        @Property(name = "kind")
        public SubagentThreadKind kind;

        @Property(name = "type")
        public String type;

        @Property(name = "description")
        public String description;

        @Property(name = "model")
        public String model;

        @Property(name = "run_count")
        public Integer runCount;

        @Property(name = "created_at_ms")
        public Long createdAtMs;

        @Property(name = "last_run_at_ms")
        public Long lastRunAtMs;

        @Property(name = "context_tokens")
        public Long contextTokens;

        @Property(name = "last_summary")
        public String lastSummary;
    }
}
