package ai.core.server.domain;

import ai.core.sandbox.SandboxConfig;
import core.framework.mongo.Field;

/**
 * @author stephen
 */
public class AgentSandboxConfig {

    public static AgentSandboxConfig fromConfig(SandboxConfig config) {
        if (config == null) return null;
        var doc = new AgentSandboxConfig();
        doc.enabled = config.enabled;
        doc.image = config.image;
        doc.memoryLimitMb = config.memoryLimitMb;
        doc.cpuLimitMillicores = config.cpuLimitMillicores;
        doc.timeoutSeconds = config.timeoutSeconds;
        doc.networkEnabled = config.networkEnabled;
        doc.gitRepoUrl = config.gitRepoUrl;
        doc.gitBranch = config.gitBranch;
        doc.tmpSizeLimit = config.tmpSizeLimit;
        doc.maxAsyncTasks = config.maxAsyncTasks;
        return doc;
    }

    @Field(name = "enabled")
    public Boolean enabled;

    @Field(name = "image")
    public String image;

    @Field(name = "memory_limit_mb")
    public Integer memoryLimitMb;

    @Field(name = "cpu_limit_millicores")
    public Integer cpuLimitMillicores;

    @Field(name = "timeout_seconds")
    public Integer timeoutSeconds;

    @Field(name = "network_enabled")
    public Boolean networkEnabled;

    @Field(name = "git_repo_url")
    public String gitRepoUrl;

    @Field(name = "git_branch")
    public String gitBranch;

    @Field(name = "tmp_size_limit")
    public String tmpSizeLimit;

    @Field(name = "max_async_tasks")
    public Integer maxAsyncTasks;

    public SandboxConfig toConfig() {
        var config = new SandboxConfig();
        config.enabled = enabled;
        config.image = image;
        config.memoryLimitMb = memoryLimitMb;
        config.cpuLimitMillicores = cpuLimitMillicores;
        config.timeoutSeconds = timeoutSeconds;
        config.networkEnabled = networkEnabled;
        config.gitRepoUrl = gitRepoUrl;
        config.gitBranch = gitBranch;
        config.tmpSizeLimit = tmpSizeLimit;
        config.maxAsyncTasks = maxAsyncTasks;
        return config;
    }
}

