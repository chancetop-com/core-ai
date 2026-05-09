package ai.core.server;

import ai.core.sandbox.SandboxProvider;
import ai.core.server.sandbox.SandboxService;
import ai.core.server.sandbox.TokenResolver;
import ai.core.server.sandbox.agentsandbox.AgentSandboxClient;
import ai.core.server.sandbox.agentsandbox.AgentSandboxExtensionsClient;
import ai.core.server.sandbox.agentsandbox.AgentSandboxProvider;
import ai.core.server.sandbox.agentsandbox.AgentSandboxProviderConfig;
import ai.core.server.sandbox.docker.DockerSandboxProvider;
import ai.core.server.sandbox.kubernetes.KubernetesClient;
import ai.core.server.sandbox.kubernetes.KubernetesSandboxProvider;
import core.framework.module.Module;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * @author stephen
 */
class SandboxModule extends Module {

    @Override
    protected void initialize() {
        property("sys.sandbox.provider").ifPresent(p -> {
            SandboxProvider provider;
            if (p.equalsIgnoreCase("kubernetes")) {
                provider = createKubernetesSandboxProvider();
            } else if (p.equalsIgnoreCase("agent-sandbox")) {
                provider = createAgentSandboxProvider();
            } else if (p.equalsIgnoreCase("docker")) {
                var socketPath = property("sys.sandbox.docker.socket").orElse("unix:///var/run/docker.sock");
                var workspaceBase = Path.of(property("sys.sandbox.docker.workspace.base").orElse("/tmp/workspaces"));
                provider = new DockerSandboxProvider(socketPath, workspaceBase, null);
            } else {
                bind(new SandboxService());
                return;
            }
            var sandboxService = bind(new SandboxService(provider));
            onShutdown(sandboxService::shutdown);
        });
        if (property("sys.sandbox.provider").isEmpty()) {
            bind(new SandboxService());
        }
    }

    private String resolveNamespace() {
        var configured = property("sys.sandbox.kubernetes.namespace").orElse(null);
        if (configured != null && !configured.isBlank()) return configured;
        try {
            return Files.readString(Path.of("/var/run/secrets/kubernetes.io/serviceaccount/namespace")).trim();
        } catch (Exception e) {
            return "core-ai-sandbox";
        }
    }

    private SandboxProvider createKubernetesSandboxProvider() {
        var namespace = resolveNamespace();
        var token = property("sys.sandbox.kubernetes.token").orElse(null);
        KubernetesClient kubernetesClient;
        if (token != null && !token.isBlank()) {
            var apiServer = property("sys.sandbox.kubernetes.apiServer").orElse("https://kubernetes.default.svc");
            kubernetesClient = new KubernetesClient(apiServer, token, namespace, 60);
        } else {
            kubernetesClient = KubernetesClient.createInCluster(namespace, 60);
        }
        var useHostPort = property("sys.sandbox.kubernetes.hostPort").orElse("false").equalsIgnoreCase("true");
        return new KubernetesSandboxProvider(kubernetesClient, null, useHostPort);
    }

    private SandboxProvider createAgentSandboxProvider() {
        var namespace = resolveNamespace();
        var token = property("sys.sandbox.kubernetes.token").orElse(null);
        var apiServer = property("sys.sandbox.kubernetes.apiServer").orElse("https://kubernetes.default.svc");
        TokenResolver tokenResolver = (token != null && !token.isBlank())
                ? TokenResolver.fixed(token)
                : TokenResolver.inCluster();
        var client = new AgentSandboxClient(apiServer, namespace, tokenResolver, 120);
        var useHostPort = property("sys.sandbox.kubernetes.hostPort").orElse("false").equalsIgnoreCase("true");
        KubernetesClient kubernetesClient = null;
        if (useHostPort) {
            if (token != null && !token.isBlank()) {
                kubernetesClient = new KubernetesClient(apiServer, token, namespace, 60);
            } else {
                kubernetesClient = KubernetesClient.createInCluster(namespace, 60);
            }
        }
        // Warm pool mode: if template name is configured, use SandboxClaim via extensions API
        var templateName = property("sys.sandbox.agentSandbox.template").orElse("core-ai-sandbox");
        var warmPoolName = property("sys.sandbox.agentSandbox.warmPool").orElse("core-ai-sandbox");
        AgentSandboxExtensionsClient extensionsClient = null;
        if (!templateName.isBlank()) {
            extensionsClient = new AgentSandboxExtensionsClient(apiServer, namespace, tokenResolver, 120);
        }
        var config = new AgentSandboxProviderConfig();
        config.client = client;
        config.extensionsClient = extensionsClient;
        config.kubernetesClient = kubernetesClient;
        config.useHostPort = useHostPort;
        config.templateName = templateName;
        config.warmPoolName = warmPoolName;
        return new AgentSandboxProvider(config);
    }
}
