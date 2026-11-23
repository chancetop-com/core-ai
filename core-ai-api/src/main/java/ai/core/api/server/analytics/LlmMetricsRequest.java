package ai.core.api.server.analytics;

import core.framework.api.web.service.QueryParam;

/**
 * @author stephen
 */
public class LlmMetricsRequest {
    /** Window size in minutes; null means the last hour. Capped at 7 days. */
    @QueryParam(name = "minutes")
    public Integer minutes;

    /** Bucket width in minutes; null means one minute, widened automatically when the window would need too many points. */
    @QueryParam(name = "bucket_minutes")
    public Integer bucketMinutes;
}
