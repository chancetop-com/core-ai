package ai.core.server.run;

import ai.core.server.domain.TriggerType;
import ai.core.server.sandbox.SandboxService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Releases a run's sandbox once the run is done. Extracted from {@link AgentRunner} (file-size budget);
 * the delay is deliberate — a workflow node's sandbox is reused by its siblings, so releasing it the
 * moment the node finishes would make the next node pay for a cold start.
 *
 * @author stephen
 */
public class RunSandboxReleaseScheduler {
    static final int DEFAULT_DELAY_SECONDS = 60;
    static final int WORKFLOW_DELAY_SECONDS = 10;
    private static final Logger LOGGER = LoggerFactory.getLogger(RunSandboxReleaseScheduler.class);

    private final SandboxService sandboxService;
    private final ScheduledExecutorService scheduler;

    public RunSandboxReleaseScheduler(SandboxService sandboxService, ScheduledExecutorService scheduler) {
        this.sandboxService = sandboxService;
        this.scheduler = scheduler;
    }

    public void schedule(String runId, TriggerType trigger) {
        var delaySeconds = trigger == TriggerType.WORKFLOW ? WORKFLOW_DELAY_SECONDS : DEFAULT_DELAY_SECONDS;
        scheduler.schedule(() -> {
            try {
                sandboxService.releaseSandbox(runId);
            } catch (Exception e) {
                LOGGER.debug("error releasing sandbox for run {}, ignored", runId, e);
            }
        }, delaySeconds, TimeUnit.SECONDS);
    }
}
