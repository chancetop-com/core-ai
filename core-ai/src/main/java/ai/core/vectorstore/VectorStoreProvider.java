package ai.core.vectorstore;

import java.util.Optional;
import java.util.function.Function;

/**
 * SPI for vector store implementations. Discovered via {@link java.util.ServiceLoader} on the classpath,
 * e.g. core-ai-vectorstore-milvus / core-ai-vectorstore-hnswlib.
 *
 * @author stephen
 */
public interface VectorStoreProvider {
    VectorStoreType type();

    /** Whether this backend is enabled by properties; e.g. sys.milvus.uri present means enabled. */
    boolean enabled(Function<String, Optional<String>> props);

    VectorStore create(Function<String, Optional<String>> props);
}
