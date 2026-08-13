package ai.core.server.domain.migration;

import core.framework.mongo.Mongo;
import org.bson.Document;

import java.util.List;

/**
 * Removes the legacy llm_model_multimodal field from system_settings documents. The field was
 * dropped from the SystemSettings entity in commit bedd6e3e, but existing documents still carry
 * it, so core-ng's entity decoder logs "undefined field" warnings on every read.
 * The collection always uses the fixed _id "default" (SystemSettingsService.SETTINGS_ID),
 * so the update targets _id to satisfy notablescan. $unset is a no-op when the field is absent.
 *
 * @author stephen
 */
public class SchemaMigrationVRemoveLegacyMultimodalModelSetting implements SchemaMigration {
    @Override
    public String version() {
        return "20260813001";
    }

    @Override
    public String description() {
        return "remove legacy llm_model_multimodal field from system_settings";
    }

    @Override
    public void migrate(Mongo mongo) {
        mongo.runCommand(new Document("update", "system_settings")
                .append("updates", List.of(new Document("q", new Document("_id", "default"))
                        .append("u", new Document("$unset", new Document("llm_model_multimodal", "")))
                        .append("multi", Boolean.TRUE))));
    }
}
