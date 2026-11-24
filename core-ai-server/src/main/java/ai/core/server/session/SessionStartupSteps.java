package ai.core.server.session;

import java.util.function.Supplier;

/**
 * A session must exist in the store before its sandbox is acquired: the workspace sandbox resolves
 * the session row to find the durable workspace. Register first, and roll the row back when the
 * following step fails.
 *
 * @author stephen
 */
final class SessionStartupSteps {

    static <T> T registerAndRun(SessionRegistry.SessionRegistration registration, SessionRegistry registry,
                                Runnable abort, Supplier<T> action) {
        registry.create(registration);
        try {
            return action.get();
        } catch (RuntimeException | Error e) {
            abort.run();
            throw e;
        }
    }

    private SessionStartupSteps() {
    }
}
