package ai.core.server;

import ai.core.server.skill.MarketplaceRepoStore;
import ai.core.server.skill.MongoMarketplaceRepoStore;
import ai.core.server.skill.MongoSkillStore;
import ai.core.server.skill.PgMarketplaceRepoStore;
import ai.core.server.skill.PgSkillStore;
import ai.core.server.skill.SkillStore;
import ai.core.server.skill.telemetry.NoopSkillTelemetryStore;
import ai.core.server.skill.telemetry.PgSkillTelemetryStore;
import ai.core.server.skill.telemetry.SkillTelemetryStore;
import core.framework.module.Module;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author stephen
 */
public class SkillStoreModule extends Module {
    private static final Logger LOGGER = LoggerFactory.getLogger(SkillStoreModule.class);

    @Override
    protected void initialize() {
        if ("pg".equals(property("sys.store.mode").orElse("mongo"))) {
            bind(SkillStore.class, bind(PgSkillStore.class));
            bind(MarketplaceRepoStore.class, bind(PgMarketplaceRepoStore.class));
            bind(SkillTelemetryStore.class, bind(PgSkillTelemetryStore.class));
        } else {
            bind(SkillStore.class, bind(MongoSkillStore.class));
            bind(MarketplaceRepoStore.class, bind(MongoMarketplaceRepoStore.class));
            LOGGER.warn("skill telemetry store disabled: sys.store.mode is not pg, binding noop implementation");
            bind(SkillTelemetryStore.class, bind(NoopSkillTelemetryStore.class));
        }
    }
}
