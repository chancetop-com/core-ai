package ai.core.server;

import ai.core.server.skill.MarketplaceRepoStore;
import ai.core.server.skill.MongoMarketplaceRepoStore;
import ai.core.server.skill.MongoSkillStore;
import ai.core.server.skill.PgMarketplaceRepoStore;
import ai.core.server.skill.PgSkillStore;
import ai.core.server.skill.SkillStore;
import core.framework.module.Module;

/**
 * @author stephen
 */
public class SkillStoreModule extends Module {
    @Override
    protected void initialize() {
        if ("pg".equals(property("sys.store.mode").orElse("mongo"))) {
            bind(SkillStore.class, bind(PgSkillStore.class));
            bind(MarketplaceRepoStore.class, bind(PgMarketplaceRepoStore.class));
        } else {
            bind(SkillStore.class, bind(MongoSkillStore.class));
            bind(MarketplaceRepoStore.class, bind(MongoMarketplaceRepoStore.class));
        }
    }
}
