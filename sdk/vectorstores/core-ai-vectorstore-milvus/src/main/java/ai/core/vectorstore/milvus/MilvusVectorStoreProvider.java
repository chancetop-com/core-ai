package ai.core.vectorstore.milvus;

import ai.core.vectorstore.VectorStore;
import ai.core.vectorstore.VectorStoreProvider;
import ai.core.vectorstore.VectorStoreType;

import java.util.Optional;
import java.util.function.Function;

/**
 * SPI entry of the Milvus backend. Enabled by the presence of {@code sys.milvus.uri}.
 *
 * @author stephen
 */
public class MilvusVectorStoreProvider implements VectorStoreProvider {
    @Override
    public VectorStoreType type() {
        return VectorStoreType.MILVUS;
    }

    @Override
    public boolean enabled(Function<String, Optional<String>> props) {
        return props.apply("sys.milvus.uri").isPresent();
    }

    @Override
    public VectorStore create(Function<String, Optional<String>> props) {
        var config = MilvusConfig.builder()
                .uri(required(props, "sys.milvus.uri"))
                .token(props.apply("sys.milvus.token").orElse(null))
                .database(props.apply("sys.milvus.database").orElse(null))
                .username(props.apply("sys.milvus.username").orElse(null))
                .password(props.apply("sys.milvus.password").orElse(null))
                .collection(props.apply("sys.milvus.collection").orElse(null))
                .contentField(props.apply("sys.milvus.content.field").orElse("query"))
                .build();
        return new MilvusVectorStore(config);
    }

    private String required(Function<String, Optional<String>> props, String key) {
        return props.apply(key).orElseThrow(() -> new IllegalArgumentException("required property not found: " + key));
    }
}
