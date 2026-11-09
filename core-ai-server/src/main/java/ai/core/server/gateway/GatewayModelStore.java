package ai.core.server.gateway;

import ai.core.server.domain.GatewayModelConfig;

import java.util.List;
import java.util.Optional;

/**
 * @author stephen
 */
public interface GatewayModelStore {
    List<GatewayModelConfig> listAll();

    Optional<GatewayModelConfig> get(String id);

    List<GatewayModelConfig> listByProviderId(String providerId);

    List<GatewayModelConfig> listByModelId(String modelId);

    void insert(GatewayModelConfig model);

    void replace(GatewayModelConfig model);

    void delete(String id);

    void deleteByProviderId(String providerId);

    void clearDefault(String providerId);

    void clearMessagesFallbackExcept(String keepId);

    boolean existsEnabledModel(String modelId);

    boolean existsEnabledModelWithEndpoint(String modelId, String endpointType);
}
