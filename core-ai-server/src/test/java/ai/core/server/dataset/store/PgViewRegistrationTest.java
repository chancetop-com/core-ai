package ai.core.server.dataset.store;

import core.framework.internal.db.DatabaseImpl;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * core-ng validates view classes when they are registered, not when a query runs, so registering
 * every dataset view here catches unsupported field types in the build instead of at pod startup.
 *
 * @author stephen
 */
class PgViewRegistrationTest {
    @Test
    void viewsPassCoreNgValidation() {
        var database = new DatabaseImpl("dataset-view-validation");
        database.url("jdbc:postgresql://localhost:5432/dataset");
        assertDoesNotThrow(() -> {
            database.view(PgDatasetRecordRow.class);
            database.view(PgJsonRow.class);
            database.view(PgCountRow.class);
            database.view(PgDatasetViewRow.class);
            database.view(PgDatasetRow.class);
            database.view(PgSizeRow.class);
            database.view(PgBoolRow.class);
        });
    }
}
