package ai.core.sandbox;

import java.util.Map;
import java.util.Set;

/**
 * @author stephen
 */
public class SandboxConfig {
    private static final Set<String> BLOCKED_ENV_NAMES = Set.of(
            "PATH", "LD_PRELOAD", "LD_LIBRARY_PATH", "HOME", "SHELL", "HOSTNAME",
            "MAX_ASYNC_TASKS"
    );

    public static SandboxConfig enabled() {
        var config = new SandboxConfig();
        config.enabled = true;
        return config;
    }

    public Boolean enabled = false;

    public String image = SandboxConstants.DEFAULT_IMAGE;

    public Integer memoryLimitMb = SandboxConstants.DEFAULT_MEMORY_LIMIT_MB;

    public Integer cpuLimitMillicores = SandboxConstants.DEFAULT_CPU_LIMIT_MILLICORES;

    public Integer timeoutSeconds = SandboxConstants.DEFAULT_TIMEOUT_SECONDS;

    public Boolean networkEnabled = false;

    public String gitRepoUrl;

    public String gitBranch = "main";

    public String tmpSizeLimit = SandboxConstants.DEFAULT_TMP_SIZE_LIMIT;

    public Integer maxAsyncTasks = SandboxConstants.DEFAULT_MAX_ASYNC_TASKS;

    public Map<String, String> env;

    public void validate() {
        if (memoryLimitMb != null) {
            if (memoryLimitMb < 64) memoryLimitMb = 64;
            if (memoryLimitMb > SandboxConstants.MAX_MEMORY_LIMIT_MB) memoryLimitMb = SandboxConstants.MAX_MEMORY_LIMIT_MB;
        }
        if (cpuLimitMillicores != null) {
            if (cpuLimitMillicores < 100) cpuLimitMillicores = 100;
            if (cpuLimitMillicores > SandboxConstants.MAX_CPU_LIMIT_MILLICORES) cpuLimitMillicores = SandboxConstants.MAX_CPU_LIMIT_MILLICORES;
        }
        if (timeoutSeconds != null) {
            if (timeoutSeconds < 300) timeoutSeconds = 300;
            if (timeoutSeconds > SandboxConstants.MAX_TIMEOUT_SECONDS) timeoutSeconds = SandboxConstants.MAX_TIMEOUT_SECONDS;
        }
        if (env != null) {
            for (var key : env.keySet()) {
                if (BLOCKED_ENV_NAMES.contains(key.toUpperCase())) {
                    throw new IllegalArgumentException("env var '" + key + "' is not allowed");
                }
            }
        }
    }
}
