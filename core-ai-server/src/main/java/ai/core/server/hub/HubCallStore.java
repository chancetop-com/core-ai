package ai.core.server.hub;

import ai.core.server.domain.HubCall;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * @author stephen
 */
public interface HubCallStore {
    void insert(HubCall call);

    void finish(String callId, long durationMs, Integer outputBytes, boolean success,
                Integer statusCode, String errorMessage);

    void attachRun(String callId, String taskId, String contextId, Long inputTokens, Long outputTokens);

    void attachResult(String callId, String refId, Long inputTokens, Long outputTokens);

    HubCallPage list(HubCallListFilter filter);

    int deleteOlderThan(ZonedDateTime cutoff);

    record HubCallPage(long total, List<HubCall> calls) {
    }
}
