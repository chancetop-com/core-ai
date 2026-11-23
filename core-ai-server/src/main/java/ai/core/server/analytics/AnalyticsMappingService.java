package ai.core.server.analytics;

import ai.core.server.domain.GatewayModelConfig;
import core.framework.inject.Inject;
import org.bson.Document;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared mapping service for model→provider and provider→name lookups
 * used by both admin analytics queries and trace daily maintenance.
 */
public class AnalyticsMappingService {
    public static Document buildProviderAddFields(Map<String, String> modelToProvider) {
        // A trace records the provider that actually served it (gateway-routed calls). Only legacy
        // rows without a stored provider fall back to the current alias-to-provider mapping.
        var fallback = switchExpression("$model", modelToProvider, "unknown");
        var missing = new Document("$or", List.of(
            new Document("$eq", List.of("$provider_id", null)),
            new Document("$eq", List.of("$provider_id", ""))));
        var expression = new Document("$cond", List.of(missing, fallback, "$provider_id"));
        return new Document("$addFields", new Document("provider_id", expression));
    }

    public static Document buildProviderNameAddFields(Map<String, String> providerIdToName) {
        return buildSwitchAddFields("provider_name", "$provider_id", providerIdToName, "unknown");
    }

    private static Document buildSwitchAddFields(String field, String inputRef,
                                                  Map<String, String> mapping, String defaultVal) {
        return new Document("$addFields", new Document(field, switchExpression(inputRef, mapping, defaultVal)));
    }

    private static Object switchExpression(String inputRef, Map<String, String> mapping, String defaultVal) {
        var branches = new ArrayList<Document>();
        for (var entry : mapping.entrySet()) {
            branches.add(new Document("case",
                new Document("$eq", List.of(inputRef, entry.getKey())))
                .append("then", entry.getValue()));
        }
        return branches.isEmpty()
            ? defaultVal
            : new Document("$switch",
                new Document("branches", branches).append("default", defaultVal));
    }

    @Inject
    ai.core.server.gateway.GatewayModelStore gatewayModelStore;
    @Inject
    ai.core.server.gateway.GatewayProviderStore gatewayProviderStore;
    @Inject
    ai.core.server.user.UserStore userStore;

    /**
     * A model is callable both as its plain {@code modelId} and as {@code provider.modelPrefix + modelId};
     * traces record the name the caller used, so both forms must resolve to the provider. An enabled
     * registration wins over a disabled one when the same modelId is registered twice.
     */
    public Map<String, String> loadModelToProviderMapping() {
        var modelPrefixes = loadProviderModelPrefixes();
        var models = gatewayModelStore.listAll();
        Map<String, String> mapping = new LinkedHashMap<>();
        Map<String, String> prefixedMapping = new LinkedHashMap<>();
        // For legacy rows without a stored provider, resolve a duplicated alias the same way the
        // router does: lowest priority number first, then the stable tie-breakers.
        var enabledModels = models.stream()
            .filter(model -> !Boolean.FALSE.equals(model.enabled))
            .sorted(Comparator
                .comparingLong((GatewayModelConfig model) -> model.priority == null ? 100L : model.priority)
                .thenComparing(model -> model.modelId == null ? "" : model.modelId)
                .thenComparing(model -> model.providerId == null ? "" : model.providerId))
            .toList();
        addModelMappings(mapping, prefixedMapping, enabledModels, modelPrefixes);
        addModelMappings(mapping, prefixedMapping, models, modelPrefixes);
        prefixedMapping.forEach(mapping::putIfAbsent);
        return mapping;
    }

    private void addModelMappings(Map<String, String> mapping, Map<String, String> prefixedMapping,
                                  List<GatewayModelConfig> models, Map<String, String> modelPrefixes) {
        for (var model : models) {
            if (model.modelId == null || model.providerId == null) continue;
            mapping.putIfAbsent(model.modelId, model.providerId);
            var prefix = modelPrefixes.get(model.providerId);
            if (prefix != null) prefixedMapping.putIfAbsent(prefix + model.modelId, model.providerId);
        }
    }

    private Map<String, String> loadProviderModelPrefixes() {
        Map<String, String> prefixes = new LinkedHashMap<>();
        for (var provider : gatewayProviderStore.listAll()) {
            if (provider.id != null && provider.modelPrefix != null && !provider.modelPrefix.isBlank()) {
                prefixes.put(provider.id, provider.modelPrefix);
            }
        }
        return prefixes;
    }

    public Map<String, String> loadProviderIdToNameMapping() {
        var providers = gatewayProviderStore.listAll();
        Map<String, String> mapping = new LinkedHashMap<>();
        for (var provider : providers) {
            if (provider.id != null && provider.name != null) {
                mapping.put(provider.id, provider.name);
            }
        }
        return mapping;
    }

    public Map<String, String> loadUserIdToNameMapping() {
        var users = userStore.listAll();
        Map<String, String> mapping = new LinkedHashMap<>();
        for (var user : users) {
            if (user.id != null && user.name != null) {
                mapping.put(user.id, user.name);
            }
        }
        return mapping;
    }
}
