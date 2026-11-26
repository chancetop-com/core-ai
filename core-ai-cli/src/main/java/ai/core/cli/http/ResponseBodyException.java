package ai.core.cli.http;

import java.io.Serial;

/**
 * A response body this client cannot turn into text: a compressed payload it cannot inflate, or
 * bytes that are not UTF-8. It is deliberately not a transport failure — {@code send} must not
 * downgrade it to a null body, because the caller would then fail on the null instead of on the
 * real cause.
 *
 * @author stephen
 */
public class ResponseBodyException extends IllegalStateException {
    @Serial
    private static final long serialVersionUID = 1L;

    public ResponseBodyException(String message) {
        super(message);
    }

    public ResponseBodyException(String message, Throwable cause) {
        super(message, cause);
    }
}
