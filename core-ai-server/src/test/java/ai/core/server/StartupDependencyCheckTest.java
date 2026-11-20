package ai.core.server;

import core.framework.db.Database;
import core.framework.mongo.Mongo;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StartupDependencyCheckTest {
    @Test
    void verifyPassesWhenMongoResponds() {
        var mongo = mock(Mongo.class);
        when(mongo.runCommand(any())).thenReturn(new Document("ok", 1.0));

        assertDoesNotThrow(() -> new StartupDependencyCheck(null, mongo).verify());
    }

    @Test
    void verifyPassesWhenPostgresResponds() {
        var database = mock(Database.class);
        var row = new PgStartupRow();
        row.ok = 1;
        when(database.selectOne(any(), any())).thenReturn(Optional.of(row));

        assertDoesNotThrow(() -> new StartupDependencyCheck(database, null).verify());
    }

    @Test
    void verifyFailsWhenMongoIsUnavailable() {
        var mongo = mock(Mongo.class);
        when(mongo.runCommand(any())).thenThrow(new RuntimeException("mongo down"));

        assertThrows(Error.class, () -> new StartupDependencyCheck(null, mongo).verify());
    }

    @Test
    void verifyFailsWhenPostgresIsUnavailable() {
        var database = mock(Database.class);
        when(database.selectOne(any(), any())).thenThrow(new RuntimeException("pg down"));

        assertThrows(Error.class, () -> new StartupDependencyCheck(database, null).verify());
    }

    @Test
    void verifyFailsWithoutAnyConfiguredStore() {
        assertThrows(Error.class, () -> new StartupDependencyCheck(null, null).verify());
    }
}
