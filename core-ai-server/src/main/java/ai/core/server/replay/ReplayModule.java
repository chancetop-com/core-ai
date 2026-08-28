package ai.core.server.replay;

import ai.core.api.server.replay.ReplayWebService;
import ai.core.server.replay.service.ReplayCleanupJob;
import ai.core.server.replay.service.ReplayExecutor;
import ai.core.server.replay.service.ReplayService;
import ai.core.server.replay.web.ReplayWebServiceImpl;
import core.framework.module.Module;

import java.time.Duration;

/**
 * Replay debug module: snapshot trace LLM spans, run edited variants and compare.
 * Depends on TraceService/ModelPricingService (TraceModule) and LLMProviders.
 *
 * @author stephen
 */
public class ReplayModule extends Module {
    @Override
    protected void initialize() {
        // bind() resolves @Inject eagerly: dependencies must be bound before their consumers
        var executor = bind(ReplayExecutor.class);
        bind(ReplayService.class);
        onShutdown(executor::shutdown);
        api().service(ReplayWebService.class, bind(ReplayWebServiceImpl.class));
        schedule().fixedRate("replay-blank-cleanup", bind(ReplayCleanupJob.class), Duration.ofHours(6));
    }
}
