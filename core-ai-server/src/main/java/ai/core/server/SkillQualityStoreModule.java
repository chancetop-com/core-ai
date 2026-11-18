package ai.core.server;

import ai.core.server.skill.quality.MongoSkillQualityStore;
import ai.core.server.skill.quality.PgSkillQualityStore;
import ai.core.server.skill.quality.SkillQualityStore;
import core.framework.module.Module;

/**
 * The skill rule catalog follows the same store switch as the skill store, so the gate cannot fall back
 * to a store that does not exist in the current deployment.
 *
 * @author core-ai
 */
public class SkillQualityStoreModule extends Module {
    @Override
    protected void initialize() {
        if ("pg".equals(property("sys.store.mode").orElse("mongo"))) {
            bind(SkillQualityStore.class, bind(PgSkillQualityStore.class));
        } else {
            bind(SkillQualityStore.class, bind(MongoSkillQualityStore.class));
        }
    }
}
