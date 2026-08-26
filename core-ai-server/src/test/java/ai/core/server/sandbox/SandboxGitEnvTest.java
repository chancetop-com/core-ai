package ai.core.server.sandbox;

import ai.core.sandbox.SandboxConfig;
import ai.core.sandbox.SandboxConstants;
import ai.core.server.sandbox.agentsandbox.SandboxCRSpecBuilder;
import ai.core.server.sandbox.kubernetes.KubernetesPodSpecBuilder;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SandboxGitEnvTest {

    @Test
    void kubernetesPodSpecIncludesFailFastGitEnv() {
        var pod = new KubernetesPodSpecBuilder(SandboxConfig.enabled(), "session-1", "user-1").build();

        var env = envMap(container(pod));

        assertGitEnv(env);
    }

    @Test
    void sandboxCrSpecIncludesFailFastGitEnv() {
        var cr = new SandboxCRSpecBuilder(SandboxConfig.enabled(), "session-1", "user-1").build();

        var spec = nested(cr, "spec");
        var podTemplate = nested(spec, "podTemplate");
        var podSpec = nested(podTemplate, "spec");
        var env = envMap(firstContainer(podSpec));

        assertGitEnv(env);
    }

    private void assertGitEnv(Map<String, String> env) {
        for (var entry : SandboxConstants.SANDBOX_GIT_ENV.entrySet()) {
            assertEquals(entry.getValue(), env.get(entry.getKey()), "env should contain " + entry.getKey());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> container(Map<String, Object> pod) {
        return firstContainer(nested(pod, "spec"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstContainer(Map<String, Object> podSpec) {
        var containers = (List<Map<String, Object>>) podSpec.get("containers");
        return containers.getFirst();
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> envMap(Map<String, Object> container) {
        var env = (List<Map<String, String>>) container.get("env");
        var result = new HashMap<String, String>();
        for (var entry : env) {
            result.put(entry.get("name"), entry.get("value"));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> nested(Map<String, Object> parent, String key) {
        return (Map<String, Object>) parent.get(key);
    }
}
