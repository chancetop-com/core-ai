package ai.core.cli.hub.dataset;

import ai.core.api.server.hub.HubDatasetView;
import ai.core.cli.hub.HubCliError;
import ai.core.cli.hub.HubExitCodes;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class DatasetRefResolverTest {
    private final DatasetRefResolver resolver = new DatasetRefResolver();

    @Test
    void idIsResolvedWithoutNameLookup() {
        var byId = view("id-1", "other-name");
        var datasets = List.of(view("id-2", "id-1"), byId);

        assertSame(byId, resolver.resolve(datasets, "id-1"));
    }

    @Test
    void uniqueNameIsResolved() {
        var byName = view("id-1", "menu-state");
        var datasets = List.of(byName, view("id-2", "other"));

        assertSame(byName, resolver.resolve(datasets, "menu-state"));
    }

    @Test
    void ambiguousNameFailsClosedWithCandidates() {
        var datasets = List.of(view("id-1", "menu-state"), view("id-2", "menu-state"));

        var error = assertThrows(HubCliError.class, () -> resolver.resolve(datasets, "menu-state"));

        assertEquals(HubExitCodes.USAGE, error.exitCode);
        assertTrue(error.getMessage().contains("id-1"), error.getMessage());
        assertTrue(error.getMessage().contains("id-2"), error.getMessage());
    }

    @Test
    void unknownReferenceIsNotFound() {
        var error = assertThrows(HubCliError.class, () -> resolver.resolve(List.of(view("id-1", "menu-state")), "ghost"));

        assertEquals(HubExitCodes.NOT_FOUND, error.exitCode);
        assertTrue(error.getMessage().contains("ghost"), error.getMessage());
    }

    private HubDatasetView view(String datasetId, String name) {
        var view = new HubDatasetView();
        view.datasetId = datasetId;
        view.name = name;
        view.type = "SESSION";
        view.permission = "WRITE";
        return view;
    }
}
