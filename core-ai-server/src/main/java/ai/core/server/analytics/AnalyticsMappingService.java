package ai.core.server.analytics;

import ai.core.server.domain.GatewayModelConfig;
import ai.core.server.domain.GatewayProviderConfig;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.bson.Document;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared mapping service for model→provider and provider→name lookups
 * used by both admin analytics queries and trace daily maintenance.
 */
public class AnalyticsMappingService {
    public static Document buildProviderAddFields(Map<String, String> modelToProvider) {
        return buildSwitchAddFields("provider_id", "$model", modelToProvider, "unknown");
    }

    public static Document buildProviderNameAddFields(Map<String, String> providerIdToName) {
        return buildSwitchAddFields("provider_name", "$provider_id", providerIdToName, "unknown");
    }

    private static Document buildSwitchAddFields(String field, String inputRef,
                                                  Map<String, String> mapping, String defaultVal) {
        var branches = new ArrayList<Document>();
        for (var entry : mapping.entrySet()) {
            branches.add(new Document("case",
                new Document("$eq", List.of(inputRef, entry.getKey())))
                .append("then", entry.getValue()));
        }
        return new Document("$addFields",
            new Document(field,
                new Document("$switch",
                    new Document("branches", branches).append("default", defaultVal))));
    }

    @Inject
    MongoCollection<GatewayModelConfig> gatewayModelCollection;
    @Inject
    MongoCollection<GatewayProviderConfig> gatewayProviderCollection;

    public Map<String, String> loadModelToProviderMapping() {
        var models = gatewayModelCollection.find(new Query());
        Map<String, String> mapping = new LinkedHashMap<>();
        for (var model : models) {
            if (model.modelId != null && model.providerId != null) {
                mapping.put(model.modelId, model.providerId);
            }
        }
        return mapping;
    }

    public Map<String, String> loadProviderIdToNameMapping() {
        var providers = gatewayProviderCollection.find(new Query());
        Map<String, String> mapping = new LinkedHashMap<>();
        for (var provider : providers) {
            if (provider.id != null && provider.name != null) {
                mapping.put(provider.id, provider.name);
            }
        }
        return mapping;
    }
}
