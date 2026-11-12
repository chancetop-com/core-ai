package ai.core.server.memory;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import core.framework.db.Database;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * @author stephen
 */
public class AgentMemoryMigrationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentMemoryMigrationService.class);
    private static final String MEMORY_MARKER = "agent_memories_v1";
    private static final String CURSOR_MARKER = "agent_memory_cursors_v1";
    private static final String LOCK_KEY = "agent_memory_migration";
    private static final int PAGE_SIZE = 200;

    @Inject
    MongoCollection<AgentMemory> memoryCollection;
    @Inject
    MongoCollection<AgentMemoryExtractionCursor> cursorCollection;
    @Inject
    AgentMemoryStore memoryStore;
    @Inject
    Database database;

    public void migrate() {
        try (var transaction = database.beginTransaction()) {
            var lock = database.selectOne("SELECT pg_try_advisory_xact_lock(hashtext(?)) AS acquired",
                    PgAgentMemoryMigrationLockRow.class, LOCK_KEY).orElse(null);
            if (lock == null || !Boolean.TRUE.equals(lock.acquired)) {
                transaction.rollback();
                LOGGER.info("agent memory migration skipped, another instance holds the lock");
                return;
            }
            if (migrationDone(MEMORY_MARKER) && migrationDone(CURSOR_MARKER)) {
                transaction.commit();
                return;
            }
            long memories = migrationDone(MEMORY_MARKER) ? 0 : copyMemories();
            long cursors = migrationDone(CURSOR_MARKER) ? 0 : copyCursors();
            insertMarker(MEMORY_MARKER);
            insertMarker(CURSOR_MARKER);
            transaction.commit();
            LOGGER.info("agent memory migration finished, memories={}, cursors={}", memories, cursors);
        }
    }

    private boolean migrationDone(String marker) {
        return database.selectOne("SELECT id FROM app.g2_migration_markers WHERE id = ?",
                PgAgentMemoryMigrationMarkerRow.class, marker).isPresent();
    }

    private void insertMarker(String marker) {
        if (!migrationDone(marker)) {
            database.execute("INSERT INTO app.g2_migration_markers (id, completed_at) VALUES (?, now())", marker);
        }
    }

    private long copyMemories() {
        long count = 0;
        String lastId = "";
        while (true) {
            var query = new Query();
            query.filter = Filters.gt("_id", lastId);
            query.sort = Sorts.ascending("_id");
            query.limit = PAGE_SIZE;
            List<AgentMemory> page = memoryCollection.find(query);
            if (page.isEmpty()) return count;
            for (var memory : page) {
                try {
                    memoryStore.insert(memory);
                } catch (RuntimeException e) {
                    LOGGER.debug("agent memory already migrated, id={}", memory.id);
                }
                lastId = memory.id;
                count++;
            }
        }
    }

    private long copyCursors() {
        long count = 0;
        String lastId = "";
        while (true) {
            var query = new Query();
            query.filter = Filters.gt("_id", lastId);
            query.sort = Sorts.ascending("_id");
            query.limit = PAGE_SIZE;
            List<AgentMemoryExtractionCursor> page = cursorCollection.find(query);
            if (page.isEmpty()) return count;
            for (var cursor : page) {
                memoryStore.saveCursor(cursor);
                lastId = cursor.id;
                count++;
            }
        }
    }
}
