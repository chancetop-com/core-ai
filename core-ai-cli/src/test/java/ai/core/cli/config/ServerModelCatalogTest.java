package ai.core.cli.config;

import ai.core.api.server.gateway.GatewayAvailableModelView;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ServerModelCatalogTest {

    @Test
    void cachesTheFetchedCatalogForTheTtl() {
        var calls = new AtomicInteger();
        var catalog = new ServerModelCatalog(() -> {
            calls.incrementAndGet();
            return List.of(model("m1"));
        });

        assertEquals("m1", catalog.models().getFirst().modelId);
        catalog.models();

        assertEquals(1, calls.get());
    }

    @Test
    void invalidateForcesARefetch() {
        var calls = new AtomicInteger();
        var catalog = new ServerModelCatalog(() -> {
            calls.incrementAndGet();
            return List.of(model("m1"));
        });

        catalog.models();
        catalog.invalidate();
        catalog.models();

        assertEquals(2, calls.get());
    }

    @Test
    void failureIsCachedAsAnUnavailableCatalog() {
        var calls = new AtomicInteger();
        var catalog = new ServerModelCatalog(() -> {
            calls.incrementAndGet();
            throw new IllegalStateException("offline");
        });

        assertNull(catalog.models());
        assertNull(catalog.models());
        assertNull(catalog.find("m1"));

        assertEquals(1, calls.get());
    }

    @Test
    void findMatchesTheModelId() {
        var catalog = new ServerModelCatalog(() -> List.of(model("m1"), model("m2")));

        assertEquals("m2", catalog.find("m2").modelId);
        assertNull(catalog.find("missing"));
        assertNull(catalog.find(null));
    }

    private GatewayAvailableModelView model(String id) {
        var model = new GatewayAvailableModelView();
        model.modelId = id;
        return model;
    }
}
