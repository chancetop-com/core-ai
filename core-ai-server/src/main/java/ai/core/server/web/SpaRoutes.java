package ai.core.server.web;

import java.util.List;

/**
 * Every browser-addressable path of the React app (core-ai-frontend/src/App.tsx). core-ng only forwards paths that
 * have an explicit route, so a page missing here works via in-app navigation but returns "path not found" on
 * refresh / direct link. SpaRoutesTest diffs this list against App.tsx to catch new pages.
 *
 * @author stephen
 */
public final class SpaRoutes {
    public static final List<String> PATHS = List.of(
        "/", "/login", "/register", "/authorize", "/chat", "/agents", "/sessions",
        "/system-prompts", "/dashboard", "/traces", "/generations", "/observability", "/skills",
        "/prompts", "/scheduler", "/tasks", "/tools", "/api-tools", "/mcp",
        "/triggers", "/datasets", "/for-you", "/for-you/artifacts", "/workflows", "/workflows/explore", "/report-issue",
        "/projects", "/notifications",
        "/experiments", "/experiments/playground", "/experiments/replay", "/experiments/replay/:id",
        "/experiments/memory", "/experiments/memory/runs/:id", "/experiments/memory/configs/:id",
        "/agents/:id", "/agents/:id/memories",
        "/workflows/:id", "/workflows/:id/runs",
        "/runs/:id",
        "/mcp/:id",
        "/system-prompts/:id",
        "/traces/:id",
        "/skills/:id", "/skills/:id/edit", "/skills/marketplace/:repoId",
        "/prompts/:id",
        "/api-tools/:id",
        "/datasets/:id", "/datasets/:id/records",
        "/projects/:id", "/projects/:id/playbook", "/projects/:id/subjects/:subjectId",
        "/shared/artifacts/:token",
        "/triggers/webhook", "/triggers/schedule", "/triggers/channels", "/triggers/openclaw",
        "/tools/builtin",
        "/settings", "/settings/users", "/settings/api-keys", "/settings/api-users", "/settings/gateway", "/settings/system",
        "/settings/cost-alerts", "/settings/tasks"
    );

    /**
     * @return true when the frontend route pattern (react-router syntax, e.g. /projects/:id) is served by one of
     * PATHS: same number of segments, literal segments equal and parameter segments matched positionally
     */
    public static boolean covers(String frontendPath) {
        var wanted = frontendPath.split("/", -1);
        for (var path : PATHS) {
            var segments = path.split("/", -1);
            if (segments.length == wanted.length && segmentsMatch(segments, wanted)) return true;
        }
        return false;
    }

    private static boolean segmentsMatch(String[] segments, String[] wanted) {
        for (int i = 0; i < segments.length; i++) {
            boolean bothParams = segments[i].startsWith(":") && wanted[i].startsWith(":");
            if (!bothParams && !segments[i].equals(wanted[i])) return false;
        }
        return true;
    }

    private SpaRoutes() {
    }
}
