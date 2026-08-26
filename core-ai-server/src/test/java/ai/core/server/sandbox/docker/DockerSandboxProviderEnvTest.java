package ai.core.server.sandbox.docker;

import ai.core.sandbox.SandboxConfig;
import ai.core.sandbox.SandboxConstants;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DockerSandboxProviderEnvTest {

    @Test
    void envListIncludesFailFastGitEnv() {
        var config = SandboxConfig.enabled();
        var provider = new DockerSandboxProvider("unix:///tmp/test.sock", Path.of("/tmp/ws"), config);

        var env = provider.buildEnvList(config);

        for (var entry : SandboxConstants.SANDBOX_GIT_ENV.entrySet()) {
            assertTrue(env.contains(entry.getKey() + "=" + entry.getValue()),
                    "docker env should contain " + entry.getKey());
        }
    }
}
