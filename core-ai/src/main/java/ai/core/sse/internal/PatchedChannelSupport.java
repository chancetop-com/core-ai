package ai.core.sse.internal;

import core.framework.web.Request;
import core.framework.web.rate.LimitRate;
import core.framework.web.sse.Channel;
import core.framework.web.sse.ChannelListener;

import java.lang.reflect.Method;

/**
 * @author miller
 */
class PatchedChannelSupport<T> {
    final ChannelListener<T> listener;
    final PatchedServerSentEventContextImpl<T> context;
    final PatchedServerSentEventWriter<T> builder;
    final LimitRate limitRate;
    final Class<T> eventClass;

    PatchedChannelSupport(ChannelListener<T> listener, Class<T> eventClass, PatchedServerSentEventContextImpl<T> context) {
        this.listener = listener;
        this.eventClass = eventClass;
        this.context = context;
        builder = new PatchedServerSentEventWriter<>();
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
