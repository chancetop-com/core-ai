package ai.core.server.session;

import ai.core.server.domain.SessionFeedback;

/**
 * @author stephen
 */
public interface SessionFeedbackStore {
    void insert(SessionFeedback feedback);

    boolean insertIfAbsent(SessionFeedback feedback);
}
