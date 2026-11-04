package ai.core.server.dataset.store;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

/**
 * Database-side aggregation request. {@code bucket} is one of hour/day/week/month or null (no time
 * bucketing), {@code timezone} is an IANA zone id used by the bucket, and {@code categoryField} is an
 * optional dataset field to group by.
 *
 * @author stephen
 */
public class AggregateRequest {
    public String datasetId;
    public ZonedDateTime from;
    public ZonedDateTime to;
    public String agentId;
    public Map<String, Object> filter;
    public String bucket;
    public String timezone;
    public String categoryField;
    public List<Metric> metrics;
}
