package ai.core.server.domain;

import com.mongodb.client.model.Filters;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;

/**
 * @author stephen
 */
public class GeminiFileRepository {
    @Inject
    MongoCollection<GeminiFile> collection;

    public GeminiFile findBySource(String userId, String providerId, String upstreamModel,
                                   String container, String blobName, String sourceETag) {
        return collection.findOne(Filters.and(
                Filters.eq("user_id", userId),
                Filters.eq("provider_id", providerId),
                Filters.eq("upstream_model", upstreamModel),
                Filters.eq("container", container),
                Filters.eq("blob_name", blobName),
                Filters.eq("source_etag", sourceETag))).orElse(null);
    }

    public void insert(GeminiFile file) {
        collection.insert(file);
    }

    public void replace(GeminiFile file) {
        collection.replace(file);
    }
}
