package ai.core.server.sandboxhub;

import ai.core.api.server.SandboxHubWebService;
import core.framework.api.web.service.Path;
import core.framework.api.web.service.PathParam;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * core-ng rejects a path variable that is not letters only at startup ("path variable must be
 * letter"), which no unit test of the service would catch — the app simply refuses to boot, and
 * only a real deployment notices.
 *
 * @author xander
 */
class SandboxHubRoutePatternTest {
    private static List<String> paths() {
        var paths = new ArrayList<String>();
        var classPath = SandboxHubWebService.class.getAnnotation(Path.class);
        if (classPath != null) {
            paths.add(classPath.value());
        }
        for (var method : SandboxHubWebService.class.getMethods()) {
            var methodPath = method.getAnnotation(Path.class);
            if (methodPath != null) {
                paths.add(methodPath.value());
            }
        }
        return paths;
    }

    private static List<String> variables(String path) {
        var variables = new ArrayList<String>();
        for (var segment : path.split("/")) {
            if (segment.startsWith(":")) {
                variables.add(segment.substring(1));
            }
        }
        return variables;
    }

    @Test
    void everyPathVariableIsLettersOnly() {
        var allPaths = paths();
        assertFalse(allPaths.isEmpty(), "no @Path found on SandboxHubWebService");
        var violations = new ArrayList<String>();
        for (var path : allPaths) {
            for (var variable : variables(path)) {
                if (!variable.chars().allMatch(Character::isLetter)) {
                    violations.add(path);
                }
            }
        }
        assertTrue(violations.isEmpty(), "path variables must be letters only: " + violations);
    }

    @Test
    void everyPathParamNamesAVariableOfItsMethodPath() {
        var violations = new ArrayList<String>();
        for (Method method : SandboxHubWebService.class.getMethods()) {
            collectPathParamViolations(method, violations);
        }
        assertTrue(violations.isEmpty(), "path params must match the method path: " + violations);
    }

    private void collectPathParamViolations(Method method, List<String> violations) {
        var methodPath = method.getAnnotation(Path.class);
        if (methodPath == null) {
            return;
        }
        var variables = variables(methodPath.value());
        for (var parameterAnnotations : method.getParameterAnnotations()) {
            for (var annotation : parameterAnnotations) {
                if (annotation instanceof PathParam pathParam && !variables.contains(pathParam.value())) {
                    violations.add(method.getName() + " -> :" + pathParam.value() + " not in " + methodPath.value());
                }
            }
        }
    }
}
