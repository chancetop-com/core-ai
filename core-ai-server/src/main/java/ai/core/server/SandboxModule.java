package ai.core.server;

import ai.core.sandbox.SandboxConfig;
import ai.core.sandbox.SandboxProvider;
import ai.core.server.blob.MinioObjectStorageService;
import ai.core.server.blob.ObjectStorageService;
import ai.core.server.file.FileService;
import ai.core.server.sandbox.SandboxService;
import ai.core.server.sandbox.TokenResolver;
import ai.core.server.sandbox.agentsandbox.AgentSandboxClient;
import ai.core.server.sandbox.agentsandbox.AgentSandboxExtensionsClient;
import ai.core.server.sandbox.agentsandbox.AgentSandboxProvider;
import ai.core.server.sandbox.agentsandbox.AgentSandboxProviderConfig;
import ai.core.server.sandbox.docker.DockerSandboxProvider;
import ai.core.server.sandbox.kubernetes.KubernetesClient;
import ai.core.server.sandbox.kubernetes.KubernetesSandboxProvider;
import ai.core.server.sandbox.snapshot.SandboxSnapshotService;
import core.framework.module.Module;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * @author stephen
 */
class SandboxModule extends Module {

    private static final String DOCKER_SERVER_HOST = "host.docker.internal";
    private static final String KUBERNETES_SERVER_HOST = "core-ai-server";

    SandboxService sandboxService;

    @Override
    protected void initialize() {
        var providerName = property("sys.sandbox.provider").orElse(null);
        if (providerName == null || providerName.isBlank()) {
            sandboxService = new SandboxService();
            bind(sandboxService);
            return;
        }

        SandboxProvider provider;
        String serverUrlFromSandbox;
        if ("kubernetes".equalsIgnoreCase(providerName)) {
            provider = createKubernetesSandboxProvider();
            serverUrlFromSandbox = resolveServerUrlFromSandbox(KUBERNETES_SERVER_HOST);
        } else if ("agent-sandbox".equalsIgnoreCase(providerName)) {
            provider = createAgentSandboxProvider();
            serverUrlFromSandbox = resolveServerUrlFromSandbox(KUBERNETES_SERVER_HOST);
        } else if ("docker".equalsIgnoreCase(providerName)) {
            var socketPath = property("sys.sandbox.docker.socket").orElse("unix:///var/run/docker.sock");
            var workspaceBase = Path.of(property("sys.sandbox.docker.workspace.base").orElse("/tmp/workspaces"));
            provider = new DockerSandboxProvider(socketPath, workspaceBase, null);
            serverUrlFromSandbox = resolveServerUrlFromSandbox(DOCKER_SERVER_HOST);
        } else {
            sandboxService = new SandboxService();
            bind(sandboxService);
            return;
        }
        sandboxService = new SandboxService(provider, resolveDefaultConfig(), serverUrlFromSandbox);
        bind(sandboxService);

        var fileService = (FileService) context.beanFactory.bean(FileService.class, null);
        sandboxService.setFileService(fileService);
        var objectStorage = (ObjectStorageService) context.beanFactory.bean(ObjectStorageService.class, null);
        sandboxService.setStorageService(objectStorage);

        configureSandboxSnapshot(objectStorage);

        onShutdown(sandboxService::shutdown);
    }

    private void configureSandboxSnapshot(ObjectStorageService objectStorage) {

        var snapshotEnabled = "true".equalsIgnoreCase(property("sys.sandbox.snapshot.enabled").orElse("false"));
        var snapshotContainer = property("azure.blob.snapshot.container").orElse("sandbox-snapshots");

        if (objectStorage instanceof MinioObjectStorageService) {
            var minioSnapshotBucket = property("storage.minio.snapshot.bucket").orElse("sandbox-snapshots");
            snapshotContainer = minioSnapshotBucket;
        }
        var snapshotService = bean(SandboxSnapshotService.class);
        snapshotService.configure(objectStorage, snapshotContainer, snapshotEnabled && objectStorage != null);
        if (sandboxService != null) {
            sandboxService.setSnapshotService(snapshotService);
        }
    }

    // Sandbox lifetime in seconds, overridable via SYS_SANDBOX_TIMEOUT; defaults to createDefaultConfig (3900s).
    private SandboxConfig resolveDefaultConfig() {
        var config = SandboxService.createDefaultConfig();
        property("sys.sandbox.timeout")
                .map(String::trim)
                .filter(v -> !v.isEmpty())
                .ifPresent(v -> config.timeoutSeconds = Integer.valueOf(v));
        return config;
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

    private String resolveServerUrlFromSandbox(String defaultHost) {
        var configured = property("sys.sandbox.server.url").orElse(null);
        if (configured != null && !configured.isBlank()) return trimTrailingSlash(configured.trim());
        return "http://" + defaultHost + ":8080";
    }

    private String trimTrailingSlash(String url) {
        if (url.endsWith("/")) return url.substring(0, url.length() - 1);
        return url;
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
        var useHostPort = "true".equalsIgnoreCase(property("sys.sandbox.kubernetes.hostPort").orElse("false"));
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
        var useHostPort = "true".equalsIgnoreCase(property("sys.sandbox.kubernetes.hostPort").orElse("false"));
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
