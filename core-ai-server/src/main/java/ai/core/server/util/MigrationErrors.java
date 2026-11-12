package ai.core.server.util;

import com.mongodb.MongoException;
import core.framework.db.UncheckedSQLException;

/**
 * Classifies the only error a copy migration may ignore: the row already exists from an
 * earlier interrupted attempt. Any other insert failure means the target schema or data is
 * wrong, and the migration must fail instead of writing its completion marker.
 *
 * @author stephen
 */
public final class MigrationErrors {
    private static final int MONGO_DUPLICATE_KEY = 11000;
    private static final String PG_UNIQUE_VIOLATION = "23505";

    public static boolean isDuplicateKey(RuntimeException e) {
        if (e instanceof MongoException mongo) return mongo.getCode() == MONGO_DUPLICATE_KEY;
        return e instanceof UncheckedSQLException sql && PG_UNIQUE_VIOLATION.equals(sql.sqlSate);
    }

    private MigrationErrors() {
    }
}
