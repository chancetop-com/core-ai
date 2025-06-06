package ai.core.rag;

import java.util.EnumMap;
import java.util.Map;

/**
 * @author stephen
 */
public class VectorStores {
    Map<VectorStoreType, VectorStore> vectorStores = new EnumMap<>(VectorStoreType.class);
    VectorStoreType defaultVectorStoreType;

    public VectorStore getDefaultVectorStore() {
        return vectorStores.get(defaultVectorStoreType);
    }

    public VectorStore getVectorStore(VectorStoreType vectorStoreType) {
        return vectorStores.get(vectorStoreType);
    }

    public void addVectorStore(VectorStoreType vectorStoreType, VectorStore vectorStore) {
        vectorStores.put(vectorStoreType, vectorStore);
    }
}
