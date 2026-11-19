package ai.core.server.a2a;

import ai.core.api.a2a.StreamResponse;
import core.framework.web.Request;
import core.framework.web.exception.BadRequestException;
import core.framework.web.sse.Channel;
import core.framework.web.sse.ChannelListener;

/**
 * Pod-to-pod SSE stream of A2A task events hosted by the owner pod.
 *
 * @author xander
 */
public class A2AEventChannelListener implements ChannelListener<StreamResponse> {
    private static final String SUBSCRIPTION_KEY = "a2a-event-subscription";
    private static final String TASK_ID_KEY = "a2a-task-id";

    private final A2AEventRelay eventRelay;

    public A2AEventChannelListener(A2AEventRelay eventRelay) {
        this.eventRelay = eventRelay;
    }

    @Override
    public void onConnect(Request request, Channel<StreamResponse> channel, String lastEventId) {
        var taskId = request.queryParams().get("task_id");
        if (taskId == null || taskId.isBlank()) {
            throw new BadRequestException("task_id is required");
        }
        var subscription = eventRelay.subscribeLocal(taskId, channel::send, channel::close);
        channel.context().put(SUBSCRIPTION_KEY, subscription);
        channel.context().put(TASK_ID_KEY, taskId);
        channel.join(taskId);
    }

    @Override
    public void onClose(Channel<StreamResponse> channel) {
        var subscription = (A2AEventRelay.Subscription) channel.context().get(SUBSCRIPTION_KEY);
        if (subscription != null) subscription.close();
    }
}
