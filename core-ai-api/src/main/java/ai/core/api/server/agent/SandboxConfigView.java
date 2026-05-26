package ai.core.api.server.agent;

import core.framework.api.json.Property;

import java.util.Map;

/**
 * @author stephen
 */
public class SandboxConfigView {
    @Property(name = "enabled")
    public Boolean enabled;

    @Property(name = "image")
    public String image;

    @Property(name = "memory_limit_mb")
    public Integer memoryLimitMb;

    @Property(name = "cpu_limit_millicores")
    public Integer cpuLimitMillicores;

    @Property(name = "timeout_seconds")
    public Integer timeoutSeconds;

    @Property(name = "network_enabled")
    public Boolean networkEnabled;

    @Property(name = "git_repo_url")
    public String gitRepoUrl;

    @Property(name = "git_branch")
    public String gitBranch;

    @Property(name = "tmp_size_limit")
    public String tmpSizeLimit;

    @Property(name = "max_async_tasks")
    public Integer maxAsyncTasks;

    @Property(name = "env_vars")
    public Map<String, String> envVars;
}
