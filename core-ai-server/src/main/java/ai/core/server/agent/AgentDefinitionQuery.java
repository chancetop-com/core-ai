package ai.core.server.agent;

import java.util.List;

/**
 * Storage-neutral agent-definition query. Scopes mirror the visibility rules the API exposes; they
 * are deliberately expressed here instead of leaking Mongo filters into 30+ callers.
 *
 * @author stephen
 */
public class AgentDefinitionQuery {

    public Scope scope = Scope.ALL;

    /** direct owner filter, or the caller for visibility scopes */
    public String userId;

    /** favorites still see their own forks while hiding other users' forks */
    public String forkVisibilityUserId;

    public String type;
    public String status;
    public String name;
    public String nameKey;
    public String nameKeyPrefix;
    public Boolean systemDefault;
    public Boolean forkedFromIsNull;
    public Boolean hasPublishedConfig;
    public List<String> ids;
    public List<String> excludeIds;

    /** catalog listings do not need the (large) system prompt */
    public boolean excludeSystemPrompt;

    /** list summaries only need the id and name */
    public boolean summaryOnly;

    /** updated_at | created_at | name_key */
    public String sortField = "updated_at";
    public boolean ascending;
    public int offset;
    public int limit = 1000;

    public enum Scope {
        ALL,
        DIRECT,
        VISIBLE,
        OWNED_OR_SYSTEM,
        OWNED_NO_SYSTEM,
        SHARED,
        WORKFLOW_MINE,
        WORKFLOW_SHARED
    }
}
