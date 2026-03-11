package ai.core.cli.remote;

import java.io.Serial;

/**
 * @author stephen
 */
public class RemoteApiException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = -1292181011779936775L;

    public final int statusCode;

    public RemoteApiException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }
}
