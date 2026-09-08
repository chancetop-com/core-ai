package ai.core.bootstrap;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class AgentBootstrapVectorStoreTest {
    @Test
    void failsWhenMilvusConfiguredButNoProviderOnClasspath() {
        var bootstrap = new AgentBootstrap(key -> "sys.milvus.uri".equals(key) ? Optional.of("http://localhost:19530") : Optional.empty());
        var error = assertThrows(IllegalStateException.class, bootstrap::initialize);
        assertTrue(error.getMessage().contains("sys.milvus.uri"));
    }

    @Test
    void failsWhenHnswlibConfiguredButNoProviderOnClasspath() {
        var bootstrap = new AgentBootstrap(key -> "sys.hnswlib.path".equals(key) ? Optional.of("/tmp/index.hnsw") : Optional.empty());
        var error = assertThrows(IllegalStateException.class, bootstrap::initialize);
        assertTrue(error.getMessage().contains("sys.hnswlib.path"));
    }
}
