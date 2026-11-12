package ai.core.server.workflow;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * @author stephen
 */
public class WorkflowRunQuery {
    public List<String> workflowIds;
    public List<String> ids;
    public List<String> excludeIds;
    public ZonedDateTime from;
    public boolean exclusiveFrom;
    public boolean ascending;
    public boolean includePreview;
    public int offset;
    public int limit = 100;
}
