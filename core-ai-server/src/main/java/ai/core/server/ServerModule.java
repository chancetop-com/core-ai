package ai.core.server;

import ai.core.MultiAgentModule;
import ai.core.api.session.AgentSessionWebService;
import ai.core.server.session.AgentSessionManager;
import ai.core.server.web.AgentSessionChannelListener;
import ai.core.server.web.AgentSessionWebServiceImpl;
import ai.core.server.web.SseAgentEvent;
import core.framework.http.HTTPMethod;
import core.framework.module.Module;

/**
 * @author stephen
 */
public class ServerModule extends Module {
    @Override
    protected void initialize() {
        loadProperties("agent.properties");
        load(new MultiAgentModule());

        bind(AgentSessionManager.class);

        api().service(AgentSessionWebService.class, bind(AgentSessionWebServiceImpl.class));

        sse().listen(HTTPMethod.GET, "/api/sessions/events", SseAgentEvent.class, bind(AgentSessionChannelListener.class));
    }
}
