package ai.core.vender;

import java.io.Serial;

/**
 * @author stephen
 */
public class VendorException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 8344293616741305579L;

    public VendorException(String message) {
        super(message);
    }

    public VendorException(String message, Throwable cause) {
        super(message, cause);
    }
}
