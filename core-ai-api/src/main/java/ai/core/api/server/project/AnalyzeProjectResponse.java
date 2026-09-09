package ai.core.api.server.project;

import core.framework.api.json.Property;

/**
 * Result of a manual "Analyze now" trigger. The analysis runs in the background (an attribution
 * pass plus one LLM call per subject takes minutes): {@code status} is "running" when the run was
 * accepted; poll {@code GET /api/projects/:id} until analysis_status leaves "running". The counters
 * are only filled by synchronous callers (tests) and stay null for background runs.
 *
 * @author stephen
 */
public class AnalyzeProjectResponse {
    @Property(name = "status")
    public String status;   // running

    @Property(name = "attributed")
    public Integer attributed;

    @Property(name = "analyzed")
    public Integer analyzed;

    @Property(name = "updated")
    public Integer updated;
}
