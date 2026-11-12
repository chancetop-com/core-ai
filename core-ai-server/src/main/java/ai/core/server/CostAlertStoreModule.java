package ai.core.server;

import ai.core.server.costalert.CostAlertEventStore;
import ai.core.server.costalert.CostAlertMigrationService;
import ai.core.server.costalert.CostAlertRuleStore;
import ai.core.server.costalert.MongoCostAlertEventStore;
import ai.core.server.costalert.MongoCostAlertRuleStore;
import ai.core.server.costalert.PgCostAlertEventStore;
import ai.core.server.costalert.PgCostAlertRuleStore;
import core.framework.module.Module;

/**
 * @author stephen
 */
public class CostAlertStoreModule extends Module {
    @Override
    protected void initialize() {
        if ("pg".equals(property("sys.store.mode").orElse("mongo"))) {
            bind(CostAlertRuleStore.class, bind(PgCostAlertRuleStore.class));
            bind(CostAlertEventStore.class, bind(PgCostAlertEventStore.class));
            var migration = bind(CostAlertMigrationService.class);
            onStartup(migration::migrate);
        } else {
            bind(CostAlertRuleStore.class, bind(MongoCostAlertRuleStore.class));
            bind(CostAlertEventStore.class, bind(MongoCostAlertEventStore.class));
        }
    }
}
