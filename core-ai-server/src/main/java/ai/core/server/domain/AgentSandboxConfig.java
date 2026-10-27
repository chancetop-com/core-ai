package ai.core.server.domain;

import ai.core.sandbox.SandboxConfig;
import core.framework.mongo.Field;

import java.util.LinkedHashMap;
import java.util.Map;

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
        doc.environmentVariables = config.env;
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

    @Field(name = "environment_variables")
    public Map<String, String> environmentVariables;

    public SandboxConfig toConfig() {
        var config = new SandboxConfig();
        applyTo(config);
        return config;
    }

    public void applyTo(SandboxConfig config) {
        if (enabled != null) config.enabled = enabled;
        if (image != null) config.image = image;
        if (memoryLimitMb != null) config.memoryLimitMb = memoryLimitMb;
        if (cpuLimitMillicores != null) config.cpuLimitMillicores = cpuLimitMillicores;
        if (timeoutSeconds != null) config.timeoutSeconds = timeoutSeconds;
        if (networkEnabled != null) config.networkEnabled = networkEnabled;
        if (gitRepoUrl != null) config.gitRepoUrl = gitRepoUrl;
        if (gitBranch != null) config.gitBranch = gitBranch;
        if (tmpSizeLimit != null) config.tmpSizeLimit = tmpSizeLimit;
        if (maxAsyncTasks != null) config.maxAsyncTasks = maxAsyncTasks;
        if (environmentVariables != null) config.env = new LinkedHashMap<>(environmentVariables);
    }
}

