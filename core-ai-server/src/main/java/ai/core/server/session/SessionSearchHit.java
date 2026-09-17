package ai.core.server.session;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * One conversation matched by {@link SessionSearchService}, carrying excerpts rather than content.
 *
 * @author stephen
 */
public class SessionSearchHit {
    public String sessionId;
    public String title;
    public ZonedDateTime lastMessageAt;
    // number of matching messages, may exceed snippets
    public int matchCount;
    public List<Snippet> snippets = new ArrayList<>();

    public static class Snippet {
        public String role;
        public ZonedDateTime createdAt;
        public String text;
    }
}
