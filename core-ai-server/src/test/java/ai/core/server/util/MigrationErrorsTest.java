package ai.core.server.util;

import com.mongodb.MongoException;
import core.framework.db.UncheckedSQLException;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class MigrationErrorsTest {
    @Test
    void duplicateKeyIsTolerated() {
        assertTrue(MigrationErrors.isDuplicateKey(new MongoException(11000, "duplicate key")));
        assertTrue(MigrationErrors.isDuplicateKey(new UncheckedSQLException(new SQLException("duplicate key", "23505"))));
    }

    @Test
    void otherFailuresMustAbortTheMigration() {
        assertFalse(MigrationErrors.isDuplicateKey(new MongoException(11001, "other")));
        assertFalse(MigrationErrors.isDuplicateKey(new UncheckedSQLException(new SQLException("not null", "23502"))));
        assertFalse(MigrationErrors.isDuplicateKey(new UncheckedSQLException(new SQLException("undefined table", "42P01"))));
        assertFalse(MigrationErrors.isDuplicateKey(new IllegalStateException("boom")));
    }
}
