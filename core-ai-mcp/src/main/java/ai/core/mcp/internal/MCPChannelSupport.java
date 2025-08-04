package ai.core.mcp.internal;

import core.framework.web.Request;
import core.framework.web.rate.LimitRate;
import core.framework.web.sse.Channel;
import core.framework.web.sse.ChannelListener;

import java.lang.reflect.Method;

/**
 * @author miller
 */
class MCPChannelSupport<T> {
    final ChannelListener<T> listener;
    final MCPServerSentEventContextImpl<T> context;
    final MCPServerSentEventWriter<T> builder;
    final LimitRate limitRate;

    MCPChannelSupport(ChannelListener<T> listener, Class<T> eventClass, MCPServerSentEventContextImpl<T> context) {
        this.listener = listener;
        this.context = context;
        builder = new MCPServerSentEventWriter<>();
        limitRate = limitRate(listener);
    }

    private LimitRate limitRate(ChannelListener<T> listener) {
        try {
            Method targetMethod = listener.getClass().getMethod("onConnect", Request.class, Channel.class, String.class);
            LimitRate limitRate = targetMethod.getDeclaredAnnotation(LimitRate.class);
            if (limitRate == null)
                limitRate = listener.getClass().getDeclaredAnnotation(LimitRate.class);
            return limitRate;
        } catch (NoSuchMethodException e) {
            throw new Error("failed to get listener.onConnect method", e);
        }
    }
}
