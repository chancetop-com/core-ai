package ai.core.server;

import ai.core.server.channel.openclaw.OcgCallbackPool;
import ai.core.server.channel.openclaw.OcgHealthCheckJob;
import ai.core.server.channel.openclaw.OcgSandboxService;
import core.framework.module.Module;

import java.time.Duration;

/**
 * @author stephen
 */
public class OpenClawModule extends Module {
    @Override
    protected void initialize() {
        var publicUrl = property("sys.public.url").orElse("http://localhost:8080");
        var ocgSandboxService = bind(OcgSandboxService.class);
        ocgSandboxService.publicUrl = publicUrl;
        onStartup(ocgSandboxService::recoverOnStartup);

        var ocgCallbackPool = bind(OcgCallbackPool.class);
        onShutdown(ocgCallbackPool::shutdown);
        schedule().fixedRate("ocg-health-check", bind(OcgHealthCheckJob.class), Duration.ofMinutes(1));
    }
}
