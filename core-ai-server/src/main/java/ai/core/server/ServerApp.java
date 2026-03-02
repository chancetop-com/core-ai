package ai.core.server;

import ai.core.MultiAgentModule;
import core.framework.module.App;
import core.framework.module.SystemModule;

/**
 * @author stephen
 */
public class ServerApp extends App {
    @Override
    protected void initialize() {
        load(new SystemModule("sys.properties"));
        loadProperties("agent.properties");
        load(new MultiAgentModule());
        load(new ServerModule());
    }
}
