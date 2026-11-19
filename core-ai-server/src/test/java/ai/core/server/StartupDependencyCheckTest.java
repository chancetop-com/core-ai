package ai.core.server;

import core.framework.mongo.Mongo;
import org.bson.Document;
import org.junit.jupiter.api.Test;

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

        assertDoesNotThrow(() -> new StartupDependencyCheck(mongo).verify());
    }

    @Test
    void verifyFailsWhenMongoIsUnavailable() {
        var mongo = mock(Mongo.class);
        when(mongo.runCommand(any())).thenThrow(new RuntimeException("mongo down"));

        assertThrows(Error.class, () -> new StartupDependencyCheck(mongo).verify());
    }
}
