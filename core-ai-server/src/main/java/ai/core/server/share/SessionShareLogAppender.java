package ai.core.server.share;

import core.framework.log.LogAppender;
import core.framework.log.message.ActionLogMessage;
import core.framework.log.message.StatMessage;

import java.util.LinkedHashMap;
import java.util.regex.Pattern;

/**
 * Masks share capabilities in framework request, database and response logs before they leave the process.
 *
 * @author Xander
 */
public class SessionShareLogAppender implements LogAppender {
    private static final Pattern SHARE_URL = Pattern.compile("(/(?:api/public/session-shares|shared/sessions)/)[A-Za-z0-9_-]+");
    private static final Pattern CAPABILITY = Pattern.compile("(?<![A-Za-z0-9_-])[A-Za-z0-9_-]{43}(?![A-Za-z0-9_-])");
    private static String mask(String value, boolean sharing) {
        if (value == null) return null;
        var masked = SHARE_URL.matcher(value).replaceAll("$1[redacted]");
        return sharing ? CAPABILITY.matcher(masked).replaceAll("[redacted]") : masked;
    }

    private final LogAppender delegate;

    public SessionShareLogAppender(LogAppender delegate) {
        this.delegate = delegate;
    }

    @Override
    public void append(ActionLogMessage message) {
        var url = message.context == null ? null : message.firstContextValue("request_url");
        boolean sharing = url != null && (url.contains("/session-shares/") || url.contains("/shared/sessions/")
            || url.contains("/api/sessions/") && url.contains("/shares"));
        if (message.context != null) {
            var context = new LinkedHashMap<>(message.context);
            context.replaceAll((key, values) -> values.stream().map(value -> mask(value, sharing)).toList());
            message.context = context;
        }
        message.errorMessage = mask(message.errorMessage, sharing);
        message.traceLog = mask(message.traceLog, sharing);
        delegate.append(message);
    }

    @Override
    public void append(StatMessage message) {
        delegate.append(message);
    }
}
