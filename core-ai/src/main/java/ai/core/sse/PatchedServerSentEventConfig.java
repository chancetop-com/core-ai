package ai.core.sse;

import ai.core.sse.internal.PatchedServerSentEventContextImpl;
import ai.core.sse.internal.PatchedServerSentEventHandler;
import ai.core.sse.internal.PatchedServerSentEventMetrics;
import core.framework.http.HTTPMethod;
import core.framework.internal.inject.InjectValidator;
import core.framework.internal.module.Config;
import core.framework.internal.module.ModuleContext;
import core.framework.internal.web.HTTPIOHandler;
import core.framework.util.Types;
import core.framework.web.sse.ChannelListener;
import core.framework.web.sse.ServerSentEventContext;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

public class PatchedServerSentEventConfig extends Config {
    private final Logger logger = LoggerFactory.getLogger(PatchedServerSentEventConfig.class);

    ModuleContext context;
    private PatchedServerSentEventMetrics metrics;
    //todo: use patched ServerSentEventHandler
    private PatchedServerSentEventHandler patchedServerSentEventHandler;

    @Override
    protected void initialize(@NotNull ModuleContext context, @Nullable String name) {
        this.context = context;
    }

    public <T> void listen(HTTPMethod method, String path, Class<T> eventClass, ChannelListener<T> listener) {
        if (HTTPIOHandler.HEALTH_CHECK_PATH.equals(path)) throw new Error("/health-check is reserved path");
        if (path.contains("/:")) throw new Error("listener path must be static, path=" + path);

        if (listener.getClass().isSynthetic())
            throw new Error("listener class must not be anonymous class or lambda, please create static class, listenerClass=" + listener.getClass().getCanonicalName());
        new InjectValidator(listener).validate();

        logger.info("sse, method={}, path={}, eventClass={}, listener={}", method, path, eventClass.getCanonicalName(), listener.getClass().getCanonicalName());

        if (patchedServerSentEventHandler == null) {
            patchedServerSentEventHandler = new PatchedServerSentEventHandler(context.logManager, context.httpServer.siteManager.sessionManager, context.httpServer.handlerContext);
            context.httpServer.sseHandler = patchedServerSentEventHandler;
            metrics = new PatchedServerSentEventMetrics();
            context.collector.metrics.add(metrics);
        }

        // todo: validate eventClass, it should be a simple POJO class, no interface, no abstract class, no generic type, and should have public no-arg constructor, and all fields should be public or have public getter/setter
        // context.beanClassValidator.validate(eventClass);
        context.apiController.beanClasses.add(eventClass);

        var sseContext = new PatchedServerSentEventContextImpl<T>();
        patchedServerSentEventHandler.add(method, path, eventClass, listener, sseContext);
        context.beanFactory.bind(Types.generic(ServerSentEventContext.class, eventClass), null, sseContext);
        metrics.contexts.add(sseContext);
        context.backgroundTask().scheduleWithFixedDelay(sseContext::keepAlive, Duration.ofSeconds(15));
    }
}
