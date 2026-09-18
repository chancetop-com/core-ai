package ai.core.vectorstore.milvus;

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
class MilvusVectorStoreProviderTest {
    @Test
    void providerIsRegisteredViaServiceLoader() {
        var providers = ServiceLoader.load(VectorStoreProvider.class).stream().map(ServiceLoader.Provider::get).toList();
        assertTrue(providers.stream().anyMatch(provider -> provider.type() == VectorStoreType.MILVUS));
    }

    @Test
    void enabledByUriProperty() {
        var provider = new MilvusVectorStoreProvider();
        assertTrue(provider.enabled(key -> "sys.milvus.uri".equals(key) ? java.util.Optional.of("http://localhost:19530") : java.util.Optional.empty()));
        assertFalse(provider.enabled(key -> java.util.Optional.empty()));
        assertEquals(VectorStoreType.MILVUS, provider.type());
    }
}
