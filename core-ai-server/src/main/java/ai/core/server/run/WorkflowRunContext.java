package ai.core.server.run;

import ai.core.server.sandbox.StagedFile;

import java.util.List;

/**
 * Everything a workflow-origin run carries beyond the plain agent run: the trace linkage and the upstream
 *  artifact files the platform stages into the child sandbox before the agent loop starts.
 *
 * @author stephen
 */
public record WorkflowRunContext(WorkflowTraceContext trace, List<StagedFile> stagedFiles) {
    public WorkflowRunContext {
        stagedFiles = stagedFiles == null ? List.of() : List.copyOf(stagedFiles);
    }
}
