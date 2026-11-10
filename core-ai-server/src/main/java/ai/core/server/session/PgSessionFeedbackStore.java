package ai.core.server.session;

import ai.core.server.domain.SessionFeedback;
import ai.core.server.pg.PgDocumentStore;
import core.framework.db.Database;
import core.framework.inject.Inject;

/**
 * @author stephen
 */
public class PgSessionFeedbackStore implements SessionFeedbackStore {
    private static final String TABLE = "app.session_feedback";

    @Inject
    public Database database;

    private PgDocumentStore<SessionFeedback> store() {
        return new PgDocumentStore<>(database, TABLE, SessionFeedback.class, feedback -> feedback.id);
    }

    @Override
    public void insert(SessionFeedback feedback) {
        store().insert(feedback);
    }

    @Override
    public boolean insertIfAbsent(SessionFeedback feedback) {
        return store().insertIfAbsent(feedback);
    }
}

