package ai.core.vectorstore.hnswlib;

import ai.core.vectorstore.VectorStore;
import ai.core.vectorstore.VectorStoreProvider;
import ai.core.vectorstore.VectorStoreType;

import java.util.Optional;
import java.util.function.Function;

/**
 * SPI entry of the HNSWLib backend. Enabled by the presence of {@code sys.hnswlib.path}.
 *
 * @author stephen
 */
public class HnswLibVectorStoreProvider implements VectorStoreProvider {
    @Override
    public VectorStoreType type() {
        return VectorStoreType.HNSW_LIB;
    }

    @Override
    public boolean enabled(Function<String, Optional<String>> props) {
        return props.apply("sys.hnswlib.path").isPresent();
    }

    @Override
    public VectorStore create(Function<String, Optional<String>> props) {
        var path = props.apply("sys.hnswlib.path")
                .orElseThrow(() -> new IllegalArgumentException("required property not found: sys.hnswlib.path"));
        return new HnswLibVectorStore(HnswConfig.of(path));
    }
}
