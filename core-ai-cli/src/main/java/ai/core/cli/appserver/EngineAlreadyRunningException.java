package ai.core.cli.appserver;

import ai.core.session.SessionLock;

import java.io.Serial;

/**
 * @author stephen
 */
public class EngineAlreadyRunningException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    private final transient SessionLock.Holder holder;

    public EngineAlreadyRunningException(SessionLock.Holder holder) {
        super("another core-ai engine is already running for this workspace" + (holder == null ? "" : ": " + holder.describe()));
        this.holder = holder;
    }

    public SessionLock.Holder holder() {
        return holder;
    }
}
