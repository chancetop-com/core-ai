package ai.core.server.hub;

import java.time.ZonedDateTime;

/**
 * Normalized query of the hub call records list: the web layer resolves request parameters
 * (range → startFrom/startTo, scope, paging) into this shape before the query service runs.
 * {@code startFrom} is mandatory — every query is anchored to a time window.
 *
 * @author stephen
 */
public class HubCallListFilter {
    public String kind;
    public String source;
    public String userId;
    public String state;
    public ZonedDateTime startFrom;
    public ZonedDateTime startTo;
    public int offset;
    public int limit;
}
