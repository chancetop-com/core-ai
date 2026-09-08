package ai.core.vectorstore;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * @author stephen
 */
class VectorStoresTest {
    @Test
    void singleStoreBecomesDefault() {
        var stores = new VectorStores();
        var store = Mockito.mock(VectorStore.class);
        stores.addVectorStore(VectorStoreType.MILVUS, store);
        assertEquals(store, stores.getDefaultVectorStore());
    }

    @Test
    void explicitDefaultWins() {
        var stores = new VectorStores();
        var milvus = Mockito.mock(VectorStore.class);
        var hnsw = Mockito.mock(VectorStore.class);
        stores.addVectorStore(VectorStoreType.MILVUS, milvus);
        stores.addVectorStore(VectorStoreType.HNSW_LIB, hnsw);
        stores.setDefaultVectorStoreType(VectorStoreType.HNSW_LIB);
        assertEquals(hnsw, stores.getDefaultVectorStore());
    }

    @Test
    void noDefaultWithoutExplicitType() {
        var stores = new VectorStores();
        stores.addVectorStore(VectorStoreType.MILVUS, Mockito.mock(VectorStore.class));
        stores.addVectorStore(VectorStoreType.HNSW_LIB, Mockito.mock(VectorStore.class));
        assertNull(stores.getDefaultVectorStore());
    }
}
