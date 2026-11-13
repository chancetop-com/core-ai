package ai.core.server;

import ai.core.server.trace.MongoTraceStore;
import ai.core.server.trace.TraceStore;
import core.framework.module.Module;

/**
 * Binds the trace store before any module that injects it: analytics, API users, memory and the trace
 * module itself all resolve their fields when they are loaded, so the binding cannot live in TraceModule.
 *
 * @author stephen
 */
public class TraceStoreModule extends Module {
    @Override
    protected void initialize() {
        bind(TraceStore.class, bind(MongoTraceStore.class));
    }
}
