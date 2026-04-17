package ai.core.server.skill;

import ai.core.server.domain.SkillDefinition;
import com.mongodb.client.model.Filters;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

/**
 * One-shot migration: materializes skills from Mongo `content` / `resources` fields
 * into SkillStorage. Idempotent — skips skills already materialized.
 * Runs at server startup.
 *
 * @author xander
 */
public class SkillStorageMigrator {
    private static final Logger LOGGER = LoggerFactory.getLogger(SkillStorageMigrator.class);

    @Inject
    MongoCollection<SkillDefinition> skillCollection;

    @Inject
    SkillStorage skillStorage;

    public void migrate() {
        var all = skillCollection.find(Filters.exists("_id"));
        int migrated = 0;
        int skipped = 0;
        int failed = 0;
        for (var def : all) {
            try {
                if (def.namespace == null || def.name == null) {
                    LOGGER.warn("skipping skill without namespace/name, id={}", def.id);
                    skipped++;
                    continue;
                }
                if (skillStorage.exists(def.namespace, def.name)) {
                    skipped++;
                    continue;
                }
                if (def.content == null || def.content.isEmpty()) {
                    LOGGER.warn("skipping skill without content, id={}, qualifiedName={}", def.id, def.qualifiedName);
                    skipped++;
                    continue;
                }
                skillStorage.writeSkillMd(def.namespace, def.name, def.content);
                if (def.resources != null) {
                    for (var r : def.resources) {
                        if (r.path == null || r.content == null) continue;
                        skillStorage.writeResource(def.namespace, def.name, r.path,
                            r.content.getBytes(StandardCharsets.UTF_8));
                    }
                }
                migrated++;
                LOGGER.info("migrated skill to storage, qualifiedName={}", def.qualifiedName);
            } catch (Exception e) {
                failed++;
                LOGGER.error("failed to migrate skill, id={}, qualifiedName={}", def.id, def.qualifiedName, e);
            }
        }
        LOGGER.info("skill storage migration complete: migrated={}, skipped={}, failed={}", migrated, skipped, failed);
    }
}
