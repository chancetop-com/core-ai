package ai.core.server.gateway;

import ai.core.server.domain.GatewayProviderConfig;

import java.util.List;
import java.util.Optional;

/**
 * @author stephen
 */
public interface GatewayProviderStore {
    List<GatewayProviderConfig> listAll();

    Optional<GatewayProviderConfig> get(String id);

    void insert(GatewayProviderConfig provider);

    void replace(GatewayProviderConfig provider);

    void delete(String id);
}
