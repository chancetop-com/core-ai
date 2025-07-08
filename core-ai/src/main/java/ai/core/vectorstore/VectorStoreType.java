package ai.core.vectorstore;

/**
 * @author stephen
 */
public enum VectorStoreType {
    MILVUS("milvus"),
    HNSW_LIB("hnswlib");

    private final String name;

    VectorStoreType(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public static VectorStoreType fromName(String name) {
        for (var type : values()) {
            if (type.getName().equalsIgnoreCase(name)) {
                return type;
            }
        }
        throw new IllegalArgumentException("No enum constant " + VectorStoreType.class.getCanonicalName() + "." + name);
    }
}
