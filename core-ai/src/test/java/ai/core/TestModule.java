package ai.core;

import core.framework.module.SystemModule;
import core.framework.test.module.AbstractTestModule;

/**
 * @author Albert
 */
public class TestModule extends AbstractTestModule {
    @Override
    protected void initialize() {
        load(new SystemModule("sys.properties"));
        loadProperties("agent.properties");
        load(new MultiAgentModule());
    }
}
