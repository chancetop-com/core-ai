package ai.core.cli.hub.dataset;

import ai.core.cli.hub.HubCliError;
import ai.core.cli.hub.HubExitCodes;

import java.util.Map;

/**
 * Resolves the session a dataset command works on: {@code --session} first, then {@code CORE_AI_SESSION_ID} (the name
 * the sandbox runtime exports, so scripts read the same in both places). There is deliberately no "latest session"
 * fallback — a session-scoped write must always name the session it lands in.
 * <p>
 * A command that can also work on a dataset directly asks {@link #resolveOrNull}: no session means dataset
 * scope, where GENERAL data is addressed by dataset id and authorized by the caller's own dataset permissions.
 *
 * @author stephen
 */
public class DatasetSessionResolver {
    public static final String ENV_SESSION_ID = "CORE_AI_SESSION_ID";

    /** The named session, or null when neither the option nor the environment names one. */
    public String resolveOrNull(String optionValue, Map<String, String> env) {
        if (hasText(optionValue)) return optionValue.trim();
        var fromEnv = env == null ? null : env.get(ENV_SESSION_ID);
        return hasText(fromEnv) ? fromEnv.trim() : null;
    }

    public String resolve(String optionValue, Map<String, String> env) {
        var sessionId = resolveOrNull(optionValue, env);
        if (sessionId != null) return sessionId;
        throw new HubCliError(HubExitCodes.USAGE,
                "missing session: pass --session <id> or set " + ENV_SESSION_ID);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
