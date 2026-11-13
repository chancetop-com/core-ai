package ai.core.agent.subagent;

/**
 * @author stephen
 */
public class SubagentModelUnavailableException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private static String message(Reason reason, String model, String provider) {
        if (reason == Reason.PROVIDER_UNAVAILABLE) {
            return "model '" + model + "' is configured for provider '" + provider + "', but that provider is not available";
        }
        return "model '" + model + "' is not available";
    }

    private final Reason reason;
    private final String model;
    private final String provider;

    public SubagentModelUnavailableException(Reason reason, String model) {
        this(reason, model, null);
    }

    public SubagentModelUnavailableException(Reason reason, String model, String provider) {
        super(message(reason, model, provider));
        this.reason = reason;
        this.model = model;
        this.provider = provider;
    }

    public Reason reason() {
        return reason;
    }

    public String model() {
        return model;
    }

    public String provider() {
        return provider;
    }

    public enum Reason {
        UNKNOWN_MODEL,
        PROVIDER_UNAVAILABLE
    }
}
