package ai.core.server.sandbox;

import core.framework.db.Database;
import core.framework.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class PgSandboxBindingStore implements SandboxBindingStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(PgSandboxBindingStore.class);
    private static final String TABLE = "app.sandbox_bindings";

    @Inject
    public Database database;

    @Override
    public String getBinding(String sessionId) {
        try {
            return database.selectOne("SELECT sandbox_id FROM " + TABLE + " WHERE session_id = ?",
                    PgSandboxBindingRow.class, sessionId)
                .map(row -> row.sandboxId)
                .orElse(null);
        } catch (RuntimeException e) {
            LOGGER.warn("failed to get sandbox binding from PostgreSQL, sessionId={}", sessionId, e);
            return null;
        }
    }

    @Override
    public void saveBinding(String sessionId, String sandboxId) {
        try {
            database.execute("INSERT INTO " + TABLE + " (session_id, sandbox_id, updated_at) VALUES (?, ?, ?) "
                    + "ON CONFLICT (session_id) DO UPDATE SET sandbox_id = EXCLUDED.sandbox_id, updated_at = EXCLUDED.updated_at",
                sessionId, sandboxId, ZonedDateTime.now());
        } catch (RuntimeException e) {
            LOGGER.warn("failed to store sandbox binding in PostgreSQL, sessionId={}", sessionId, e);
        }
    }

    @Override
    public void deleteBinding(String sessionId) {
        try {
            deleteBindingStrict(sessionId);
        } catch (RuntimeException e) {
            LOGGER.warn("failed to delete sandbox binding from PostgreSQL, sessionId={}", sessionId, e);
        }
    }

    @Override
    public void deleteBindingStrict(String sessionId) {
        database.execute("DELETE FROM " + TABLE + " WHERE session_id = ?", sessionId);
    }
}
