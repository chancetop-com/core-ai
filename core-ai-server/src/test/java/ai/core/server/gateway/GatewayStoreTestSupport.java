package ai.core.server.gateway;

import ai.core.server.domain.GatewayModelConfig;
import ai.core.server.domain.GatewayProviderConfig;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @author stephen
 */
public final class GatewayStoreTestSupport {
    @SuppressWarnings("unchecked")
    public static GatewayProviderStore providerStore(List<GatewayProviderConfig> providers) {
        var collection = (MongoCollection<GatewayProviderConfig>) mock(MongoCollection.class);
        when(collection.find(any(Query.class))).thenReturn(providers);
        return MongoGatewayProviderStore.of(collection);
    }

    @SuppressWarnings("unchecked")
    public static GatewayModelStore modelStore(List<GatewayModelConfig> models) {
        var collection = (MongoCollection<GatewayModelConfig>) mock(MongoCollection.class);
        when(collection.find(any(Query.class))).thenReturn(models);
        return MongoGatewayModelStore.of(collection);
    }

    private GatewayStoreTestSupport() {
    }
}
