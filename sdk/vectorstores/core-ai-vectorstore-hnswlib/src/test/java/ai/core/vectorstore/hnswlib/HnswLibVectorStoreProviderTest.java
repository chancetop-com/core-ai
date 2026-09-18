package ai.core.vectorstore.hnswlib;

import ai.core.vectorstore.VectorStoreProvider;
import ai.core.vectorstore.VectorStoreType;
import org.junit.jupiter.api.Test;

import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class HnswLibVectorStoreProviderTest {
    @Test
    void providerIsRegisteredViaServiceLoader() {
        var providers = ServiceLoader.load(VectorStoreProvider.class).stream().map(ServiceLoader.Provider::get).toList();
        assertTrue(providers.stream().anyMatch(provider -> provider.type() == VectorStoreType.HNSW_LIB));
    }

    @Test
    void enabledByPathProperty() {
        var provider = new HnswLibVectorStoreProvider();
        assertTrue(provider.enabled(key -> "sys.hnswlib.path".equals(key) ? java.util.Optional.of("/tmp/index.hnsw") : java.util.Optional.empty()));
        assertFalse(provider.enabled(key -> java.util.Optional.empty()));
        assertEquals(VectorStoreType.HNSW_LIB, provider.type());
    }
}
