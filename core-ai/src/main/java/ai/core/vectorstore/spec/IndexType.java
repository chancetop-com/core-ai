package ai.core.vectorstore.spec;

/**
 * Vector index types supported by {@link CollectionSpec}.
 *
 * @author stephen
 */
public enum IndexType {
    FLAT,
    IVF_FLAT,
    HNSW,
    AUTOINDEX
}
