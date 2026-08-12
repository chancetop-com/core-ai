package ai.core.server.costalert;

import ai.core.api.server.costalert.CostAlertAdminWebService;
import core.framework.module.Module;

import java.time.Duration;

/**
 * Cost alert module: rule evaluation job and admin management API.
 *
 * @author stephen
 */
public class CostAlertModule extends Module {
    @Override
    protected void initialize() {
        bind(CostAlertService.class);
        schedule().fixedRate("cost-alert", bind(CostAlertJob.class), Duration.ofMinutes(30));
        api().service(CostAlertAdminWebService.class, bind(CostAlertAdminWebServiceImpl.class));
    }
}
