package ai.core.server.domain.migration;

import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import core.framework.mongo.Mongo;

/**
 * @author xander
 */
public class SchemaMigrationVSeoOpsIndexes implements SchemaMigration {
    @Override
    public String version() {
        return "20260817001";
    }

    @Override
    public String description() {
        return "create SEO operations identity and query indexes";
    }

    @Override
    public void migrate(Mongo mongo) {
        mongo.createIndex("seo_merchants", Indexes.ascending("slug"), new IndexOptions().unique(true));
        mongo.createIndex("seo_merchants", Indexes.ascending("creation_idempotency_key"), new IndexOptions().unique(true));
        mongo.createIndex("seo_merchants", Indexes.ascending("operator_user_ids"), new IndexOptions());

        mongo.createIndex("seo_locations", Indexes.compoundIndex(Indexes.ascending("merchant_id"), Indexes.ascending("slug")), new IndexOptions().unique(true));
        mongo.createIndex("seo_locations", Indexes.compoundIndex(Indexes.ascending("merchant_id"), Indexes.ascending("creation_idempotency_key")), new IndexOptions().unique(true));
        mongo.createIndex("seo_locations", Indexes.compoundIndex(Indexes.ascending("merchant_id"), Indexes.ascending("readiness_status")), new IndexOptions());

        mongo.createIndex("seo_tasks", Indexes.compoundIndex(Indexes.ascending("merchant_id"), Indexes.ascending("creation_idempotency_key")), new IndexOptions().unique(true));
        mongo.createIndex("seo_tasks", Indexes.compoundIndex(Indexes.ascending("merchant_id"), Indexes.ascending("status"), Indexes.ascending("due_at")), new IndexOptions());
        mongo.createIndex("seo_tasks", Indexes.compoundIndex(Indexes.ascending("owner_id"), Indexes.ascending("status"), Indexes.ascending("due_at")), new IndexOptions());
        mongo.createIndex("seo_tasks", Indexes.compoundIndex(Indexes.ascending("merchant_id"), Indexes.descending("updated_at")), new IndexOptions());
    }
}
