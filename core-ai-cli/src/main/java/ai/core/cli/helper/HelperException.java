package ai.core.cli.helper;

import java.io.Serial;

/**
 * Business error of the helper management surface; {@code code} becomes the app-server business
 * error code ({@code HELPER_EXISTS} / {@code HELPER_LIMIT} / ...).
 *
 * @author stephen
 */
public class HelperException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    private final String code;

    public HelperException(String code, String message) {
        super(message);
        this.code = code;
    }

    public HelperException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
