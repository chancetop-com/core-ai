package ai.core.server.session;

import ai.core.server.domain.ChatMessage;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import core.framework.db.Database;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One-way copy of chat messages from Mongo to PostgreSQL. Insert-only and marked like the session
 * copy, so a rolling cutover can be replayed without overwriting rows the PostgreSQL runtime owns.
 *
 * @author stephen
 */
public class ChatMessageMigrationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChatMessageMigrationService.class);
    private static final String MARKER = "chat_messages_v1";
    private static final int PAGE_SIZE = 200;
    private static final String LOCK_KEY = "chat_message_migration";

    @Inject
    MongoCollection<ChatMessage> chatMessageCollection;
    @Inject
    ChatMessageStore chatMessageStore;
    @Inject
    Database database;

    public void migrate() {
        try (var transaction = database.beginTransaction()) {
            var lock = database.selectOne("SELECT pg_try_advisory_xact_lock(hashtext(?)) AS acquired",
                    PgChatMessageMigrationLockRow.class, LOCK_KEY).orElse(null);
            if (lock == null || !Boolean.TRUE.equals(lock.acquired)) {
                transaction.rollback();
                LOGGER.info("chat message migration skipped, another instance holds the lock");
                return;
            }
            if (migrationDone()) {
                transaction.commit();
                return;
            }
            long count = copy();
            database.execute("INSERT INTO app.g2_migration_markers (id, completed_at) VALUES (?, now())", MARKER);
            transaction.commit();
            LOGGER.info("chat message migration finished, messages={}", count);
        }
    }

    private boolean migrationDone() {
        return database.selectOne("SELECT id FROM app.g2_migration_markers WHERE id = ?",
                PgChatMessageMigrationMarkerRow.class, MARKER).isPresent();
    }

    private long copy() {
        long count = 0;
        String lastId = "";
        while (true) {
            var query = new Query();
            query.filter = Filters.gt("_id", lastId);
            query.sort = Sorts.ascending("_id");
            query.limit = PAGE_SIZE;
            var page = chatMessageCollection.find(query);
            if (page.isEmpty()) return count;
            for (var message : page) {
                chatMessageStore.insertIfAbsent(message);
                lastId = message.id;
                count++;
            }
        }
    }
}
