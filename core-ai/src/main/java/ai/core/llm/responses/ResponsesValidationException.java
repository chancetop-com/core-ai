package ai.core.llm.responses;

public class ResponsesValidationException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ResponsesValidationException(String message) {
        super(message);
    }
}
