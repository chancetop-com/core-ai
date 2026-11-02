package ai.core.session;

import java.io.Serial;

/**
 * @author stephen
 */
public class UnsupportedSnapshotVersionException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    private final int version;

    public UnsupportedSnapshotVersionException(int version) {
        super("session snapshot version " + version + " is newer than supported version " + SnapshotVersion.CURRENT);
        this.version = version;
    }

    public int version() {
        return version;
    }
}
