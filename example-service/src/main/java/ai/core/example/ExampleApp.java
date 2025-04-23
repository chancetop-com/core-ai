package ai.core.example;

import ai.core.MultiAgentModule;
import core.framework.module.App;
import core.framework.module.SystemModule;

/**
 * @author stephen
 */
public class ExampleApp extends App {
    @Override
    protected void initialize() {
        load(new SystemModule("sys.properties"));
        load(new MultiAgentModule());
        load(new ExampleModule());
    }
}
