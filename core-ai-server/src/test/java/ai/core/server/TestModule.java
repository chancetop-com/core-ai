package ai.core.server;

import core.framework.module.SystemModule;
import core.framework.test.module.AbstractTestModule;

/**
 * @author stephen
 */
public class TestModule extends AbstractTestModule {
    @Override
    protected void initialize() {
        load(new SystemModule("sys.properties"));
        load(new ServerModule());
    }
}
