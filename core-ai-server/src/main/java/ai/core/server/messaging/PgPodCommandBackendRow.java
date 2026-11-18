package ai.core.server.messaging;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgPodCommandBackendRow {
    @Column(name = "command_backend")
    public String commandBackend;
}
