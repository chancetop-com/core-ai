package ai.core.cli.appserver;

import java.util.regex.Pattern;

/**
 * RPC boundary policy for session ids. Ids are opaque to clients, but they are also file names, so
 * only a conservative character set is accepted before the value reaches persistence.
 *
 * @author stephen
 */
public final class SessionIds {
    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9._~-]{1,200}$");

    public static String check(String id) {
        if (id == null || !ID.matcher(id).matches()) {
            throw RpcException.invalidParams("invalid sessionId: " + id);
        }
        return id;
    }

    private SessionIds() {
    }
}
