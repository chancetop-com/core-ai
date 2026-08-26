package ai.core.server;

import ai.core.api.server.trace.TraceControlWebService;
import ai.core.server.trace.service.TraceStopService;
import ai.core.server.trace.web.trace.TraceControlWebServiceImpl;
import core.framework.module.Module;

/**
 * Loads after MessagingRuntimeModule and AgentRunnerModule: stopping a trace injects
 * CommandPublisher, TurnStateRegistry and AgentRunService, which TraceModule (loaded earlier) cannot see.
 *
 * @author Xander
 */
public class TraceControlModule extends Module {
    @Override
    protected void initialize() {
        bind(TraceStopService.class);
        api().service(TraceControlWebService.class, bind(TraceControlWebServiceImpl.class));
    }
}
