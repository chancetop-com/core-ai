package ai.core.server.web;

import core.framework.http.HTTPMethod;
import core.framework.internal.web.controller.ControllerHolder;
import core.framework.internal.web.route.PathPatternValidator;
import core.framework.internal.web.route.Route;
import core.framework.web.Controller;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards against frontend pages that only work via in-app navigation: every react-router path declared in App.tsx
 * must be registered on the server, otherwise refreshing the browser on that page returns "path not found".
 */
class SpaRoutesTest {
    private static final Path APP_TSX = Path.of("../core-ai-frontend/src/App.tsx");
    private static final Pattern ROUTE_PATH = Pattern.compile("<Route\s+path=\"([^\"]+)\"");

    @Test
    void everyFrontendRouteIsServedOnRefresh() throws IOException {
        assertTrue(Files.exists(APP_TSX), "frontend routes file not found: " + APP_TSX.toAbsolutePath());
        var frontendPaths = frontendRoutePaths(Files.readAllLines(APP_TSX, StandardCharsets.UTF_8));
        assertFalse(frontendPaths.isEmpty(), "no <Route path=...> found in " + APP_TSX);

        var missing = frontendPaths.stream().filter(path -> !SpaRoutes.covers(path)).toList();
        assertTrue(missing.isEmpty(), "add these frontend routes to SpaRoutes.PATHS so they survive a browser refresh: " + missing);
    }

    @Test
    void coversMatchesParamsPositionally() {
        assertTrue(SpaRoutes.covers("/projects/:projectId/subjects/:sid"));
        assertTrue(SpaRoutes.covers("/mcp"));
        assertFalse(SpaRoutes.covers("/projects/:id/unknown"));
        assertFalse(SpaRoutes.covers("/no-such-page"));
    }

    // core-ng rejects conflicting patterns (e.g. two different param names under the same parent) at startup;
    // registering the whole table into a real route tree catches that before deploy
    @Test
    void pathsRegisterIntoCoreNgRouteTreeWithoutConflict() throws NoSuchMethodException {
        var route = new Route();
        Controller controller = request -> null;
        var holder = new ControllerHolder(controller, Object.class.getMethod("toString"), "spa", "spa", false);
        for (var path : SpaRoutes.PATHS) {
            assertDoesNotThrow(() -> new PathPatternValidator(path, true).validate(), path);
            assertDoesNotThrow(() -> route.add(HTTPMethod.GET, path, holder), path);
        }
    }

    // resolves nested <Route> blocks (relative child paths such as "api-keys" under "/settings"); wildcard routes
    // are client-side redirects and need no server route
    private List<String> frontendRoutePaths(List<String> lines) {
        var paths = new ArrayList<String>();
        Deque<String> parents = new ArrayDeque<>();
        for (var rawLine : lines) {
            var line = rawLine.trim();
            var matcher = ROUTE_PATH.matcher(line);
            if (matcher.find()) {
                var path = matcher.group(1);
                var absolute = path.startsWith("/") ? path : parents.peek() + "/" + path;
                if (!absolute.contains("*")) paths.add(absolute);
                if (!line.endsWith("/>")) parents.push(absolute);
            } else if (line.contains("</Route>") && !parents.isEmpty()) {
                parents.pop();
            }
        }
        return paths;
    }
}
