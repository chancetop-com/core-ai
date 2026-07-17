package ai.core.server;

import ai.core.server.selfharness.SelfHarnessApiCaller;
import ai.core.server.selfharness.SelfHarnessTools;
import core.framework.module.Module;

/**
 * @author stephen
 */
public class SelfHarnessModule extends Module {
    @Override
    protected void initialize() {
        bind(SelfHarnessApiCaller.class);
        var selfHarnessTools = bind(SelfHarnessTools.class);
        onStartup(selfHarnessTools::initialize);
    }
}
