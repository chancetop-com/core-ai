package ai.core.cli.hub.dataset;

import ai.core.cli.hub.HubCliError;
import ai.core.cli.hub.HubExitCodes;

import java.util.Map;

/**
 * Resolves the session a dataset command works on: {@code --session} first, then {@code CORE_AI_SESSION_ID} (the name
 * the sandbox runtime exports, so scripts read the same in both places). There is deliberately no "latest session"
 * fallback — a write must always name the session it lands in.
 *
 * @author stephen
 */
public class DatasetSessionResolver {
    public static final String ENV_SESSION_ID = "CORE_AI_SESSION_ID";

    public String resolve(String optionValue, Map<String, String> env) {
        if (hasText(optionValue)) return optionValue.trim();
        var fromEnv = env == null ? null : env.get(ENV_SESSION_ID);
        if (hasText(fromEnv)) return fromEnv.trim();
        throw new HubCliError(HubExitCodes.USAGE,
                "missing session: pass --session <id> or set " + ENV_SESSION_ID);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
