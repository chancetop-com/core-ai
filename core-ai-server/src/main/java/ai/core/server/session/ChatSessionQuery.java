package ai.core.server.session;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * Storage-neutral filter for chat session lookups. Only fields that a caller actually filters on are
 * declared here; Mongo and PostgreSQL translate the same query so read paths cannot drift apart.
 *
 * @author stephen
 */
public class ChatSessionQuery {
    public String userId;
    public List<String> agentIds;
    public List<String> sources;
    public List<String> ids;
    public List<String> excludeIds;
    public String comparisonId;
    public String comparisonPairId;

    /** when true, only sessions carrying at least one artifact are returned */
    public Boolean hasArtifacts;

    /** when set, only sessions carrying an artifact for one of these file ids are returned */
    public List<String> artifactFileIds;

    /** created_at | last_message_at */
    public String timeField = "last_message_at";
    public ZonedDateTime from;
    public ZonedDateTime to;

    public boolean includeDeleted;
    public boolean excludeComparisonVariantB;

    public String sortField = "last_message_at";
    public boolean ascending;
    public int offset;
    /** null or non-positive means no limit; the Mongo store must never receive a sentinel length because core-ng pre-sizes the result list with it */
    public Integer limit = 100;
}
