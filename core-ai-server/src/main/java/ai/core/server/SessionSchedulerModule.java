package ai.core.server;

import ai.core.schedule.ScheduledTaskStore;
import ai.core.server.schedule.MongoScheduledTaskStore;
import ai.core.server.schedule.SessionScheduler;
import ai.core.server.schedule.SessionSchedulerJob;
import ai.core.server.tool.ToolRegistryService;
import core.framework.module.Module;

import java.time.Duration;

/**
 * Fires session-bound scheduled tasks (created via the scheduled_task tool) by
 * injecting a message back into the originating session.
 * <p>
 * Loaded after {@link MessagingRuntimeModule} because {@link SessionScheduler}
 * depends on the {@link ai.core.server.messaging.CommandPublisher}.
 *
 * @author stephen
 */
public class SessionSchedulerModule extends Module {

    @Override
    protected void initialize() {
        bind(MongoScheduledTaskStore.class);
        // register the same instance under the interface so @Inject ScheduledTaskStore fields resolve
        bind(ScheduledTaskStore.class, bean(MongoScheduledTaskStore.class));
        bind(SessionScheduler.class);
        // wire the store into the tool registry so every session gets the scheduled_task tool
        bean(ToolRegistryService.class).setScheduledTaskStore(bean(MongoScheduledTaskStore.class));
        schedule().fixedRate("session-scheduler", bind(SessionSchedulerJob.class), Duration.ofMinutes(1));
    }
}
