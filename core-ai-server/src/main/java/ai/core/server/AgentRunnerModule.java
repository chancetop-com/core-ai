package ai.core.server;

import ai.core.api.server.AgentRunWebService;
import ai.core.api.server.AgentScheduleWebService;
import ai.core.server.run.AgentRunBuilder;
import ai.core.server.run.AgentRunService;
import ai.core.server.run.AgentRunTracer;
import ai.core.server.run.AgentRunner;
import ai.core.server.schedule.AgentScheduleService;
import ai.core.server.schedule.AgentScheduler;
import ai.core.server.schedule.AgentSchedulerJob;
import ai.core.server.trigger.action.RunAgentAction;
import ai.core.server.trigger.TriggerController;
import ai.core.server.web.AgentRunWebServiceImpl;
import ai.core.server.web.AgentScheduleWebServiceImpl;
import core.framework.http.HTTPMethod;
import core.framework.module.Module;

import java.time.Duration;

/**
 * @author stephen
 */
public class AgentRunnerModule extends Module {

    @Override
    protected void initialize() {
        bindServices();
        bindScheduledJobs();
        bindWebServices();
        registerWebhookTrigger();
    }

    private void bindServices() {
        bind(AgentRunTracer.class);
        bind(AgentRunBuilder.class);
        bind(AgentRunner.class);
        onShutdown(bean(AgentRunner.class)::shutdown);
        bind(AgentRunService.class);
        bind(AgentScheduler.class);
        bind(AgentScheduleService.class);
        bind(RunAgentAction.class);
    }

    private void bindScheduledJobs() {
        schedule().fixedRate("agent-scheduler", bind(AgentSchedulerJob.class), Duration.ofMinutes(1));
    }

    private void bindWebServices() {
        api().service(AgentRunWebService.class, bind(AgentRunWebServiceImpl.class));
        api().service(AgentScheduleWebService.class, bind(AgentScheduleWebServiceImpl.class));
    }

    private void registerWebhookTrigger() {
        var controller = bind(TriggerController.class);
        http().route(HTTPMethod.POST, "/api/webhook-triggers/:id", controller);
        // GET for Slack URL verification challenge
        http().route(HTTPMethod.GET, "/api/webhook-triggers/:id", controller);
    }
}
