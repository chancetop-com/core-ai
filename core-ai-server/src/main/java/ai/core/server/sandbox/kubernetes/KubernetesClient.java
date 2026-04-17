package ai.core.server.sandbox.kubernetes;

import core.framework.api.json.Property;
import core.framework.http.ContentType;
import core.framework.http.HTTPClient;
import core.framework.http.HTTPMethod;
import core.framework.http.HTTPRequest;
import core.framework.http.HTTPResponse;
import core.framework.json.JSON;
import core.framework.util.Files;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * @author stephen
 */
public class KubernetesClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(KubernetesClient.class);
    private static final String IN_CLUSTER_TOKEN_PATH = "/var/run/secrets/kubernetes.io/serviceaccount/token";
    private static final String IN_CLUSTER_NAMESPACE_PATH = "/var/run/secrets/kubernetes.io/serviceaccount/namespace";
    private static final String IN_CLUSTER_API_SERVER = "https://kubernetes.default.svc";

    public static KubernetesClient createInCluster(String namespaceOverride, int timeoutSeconds) {
        var namespace = namespaceOverride;
        if (namespace == null || namespace.isBlank()) {
            namespace = Files.text(Path.of(IN_CLUSTER_NAMESPACE_PATH));
        }
        LOGGER.info("using in-cluster kubernetes credentials, namespace={}", namespace);
        return new KubernetesClient(IN_CLUSTER_API_SERVER, namespace, timeoutSeconds, true);
    }

    private final String apiServer;
    private final String namespace;
    private final HTTPClient httpClient;
    private final String token; // null for in-cluster mode
    private final boolean inCluster;

    public KubernetesClient(String apiServer, String token, String namespace, int timeoutSeconds) {
        this(apiServer, namespace, timeoutSeconds, token, false);
    }

    public KubernetesClient(String apiServer, String namespace, int timeoutSeconds, boolean inCluster) {
        this(apiServer, namespace, timeoutSeconds, null, inCluster);
    }

    public KubernetesClient(String apiServer, String namespace, int timeoutSeconds, String token, boolean inCluster) {
        this.apiServer = apiServer.trim().replaceAll("/+$", "");
        this.namespace = namespace;
        this.token = token;
        this.inCluster = inCluster;
        this.httpClient = HTTPClient.builder()
                .trustAll()
                .connectTimeout(Duration.ofSeconds(10))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .build();
    }

    private String resolveToken() {
        if (inCluster) {
            // re-read token on each call to handle token rotation
            return Files.text(Path.of(IN_CLUSTER_TOKEN_PATH));
        }
        return token;
    }

    public PodInfo createPod(String podJson) {
        var url = apiServer + "/api/v1/namespaces/" + namespace + "/pods";
        var response = post(url, podJson);
        if (response.statusCode != 201) {
            throw new RuntimeException("Failed to create pod: " + response.statusCode + " " + response.text());
        }
        return JSON.fromJSON(PodInfo.class, response.text());
    }

    public Optional<PodInfo> getPod(String podName) {
        var url = apiServer + "/api/v1/namespaces/" + namespace + "/pods/" + podName;
        var response = get(url);
        if (response.statusCode == 404) {
            return Optional.empty();
        }
        if (response.statusCode != 200) {
            throw new RuntimeException("Failed to get pod: " + response.statusCode + " " + response.text());
        }
        return Optional.of(JSON.fromJSON(PodInfo.class, response.text()));
    }

    public PodInfo waitForReady(String podName) {
        var startTime = System.currentTimeMillis();
        var pollInterval = 2000; // 2 seconds

        while (System.currentTimeMillis() - startTime < (60 * 1000)) { // 60 seconds max
            var podOpt = getPod(podName);
            if (podOpt.isEmpty()) {
                throw new RuntimeException("Pod not found: " + podName);
            }
            var pod = podOpt.get();
            if (isPodReady(pod)) {
                return pod;
            }
            sleep(pollInterval);
        }
        throw new RuntimeException("Pod readiness timeout: " + podName);
    }

    public void deletePod(String podName) {
        var url = apiServer + "/api/v1/namespaces/" + namespace + "/pods/" + podName + "?gracePeriodSeconds=5";
        var response = delete(url);
        // 200, 202, or 404 are all acceptable (404 means already deleted)
        if (response.statusCode != 200 && response.statusCode != 202 && response.statusCode != 404) {
            throw new RuntimeException("Failed to delete pod: " + response.statusCode + " " + response.text());
        }
    }

    public List<PodInfo> listPods(String labelSelector) {
        var url = apiServer + "/api/v1/namespaces/" + namespace + "/pods?labelSelector=" + labelSelector;
        var response = get(url);
        if (response.statusCode != 200) {
            throw new RuntimeException("Failed to list pods: " + response.statusCode + " " + response.text());
        }
        var podList = JSON.fromJSON(PodList.class, response.text());
        return podList.items != null ? podList.items : List.of();
    }

    public ServiceInfo createNodePortService(String serviceName, String podName, int containerPort) {
        var service = Map.of(
            "apiVersion", "v1",
            "kind", "Service",
            "metadata", Map.of("name", serviceName, "labels", Map.of("component", "sandbox")),
            "spec", Map.of(
                "type", "NodePort",
                "selector", Map.of("sandbox-name", podName),
                "ports", List.of(Map.of("port", containerPort, "targetPort", containerPort, "protocol", "TCP"))
            )
        );
        var url = apiServer + "/api/v1/namespaces/" + namespace + "/services";
        var response = post(url, JSON.toJSON(service));
        if (response.statusCode != 201) {
            throw new RuntimeException("Failed to create service: " + response.statusCode + " " + response.text());
        }
        return JSON.fromJSON(ServiceInfo.class, response.text());
    }

    public void deleteService(String serviceName) {
        var url = apiServer + "/api/v1/namespaces/" + namespace + "/services/" + serviceName;
        var response = delete(url);
        if (response.statusCode != 200 && response.statusCode != 202 && response.statusCode != 404) {
            throw new RuntimeException("Failed to delete service: " + response.statusCode + " " + response.text());
        }
    }

    public List<ServiceInfo> listServices(String labelSelector) {
        var url = apiServer + "/api/v1/namespaces/" + namespace + "/services?labelSelector=" + labelSelector;
        var response = get(url);
        if (response.statusCode != 200) {
            throw new RuntimeException("Failed to list services: " + response.statusCode + " " + response.text());
        }
        var serviceList = JSON.fromJSON(ServiceList.class, response.text());
        return serviceList.items != null ? serviceList.items : List.of();
    }

    public String getPodLogs(String podName, int tailLines) {
        var url = apiServer + "/api/v1/namespaces/" + namespace + "/pods/" + podName + "/log?tailLines=" + tailLines;
        var response = get(url);
        if (response.statusCode != 200) {
            return "Failed to get logs: " + response.statusCode;
        }
        return response.text();
    }

    private HTTPResponse get(String url) {
        try {
            var req = new HTTPRequest(HTTPMethod.GET, url);
            req.headers.put("Authorization", "Bearer " + resolveToken());
            return httpClient.execute(req);
        } catch (Exception e) {
            throw new RuntimeException("Kubernetes API request failed: " + url, e);
        }
    }

    private HTTPResponse post(String url, String body) {
        try {
            var req = new HTTPRequest(HTTPMethod.POST, url);
            req.headers.put("Authorization", "Bearer " + resolveToken());
            req.headers.put("Content-Type", "application/json");
            req.body(body, ContentType.APPLICATION_JSON);
            return httpClient.execute(req);
        } catch (Exception e) {
            throw new RuntimeException("Kubernetes API request failed: " + url, e);
        }
    }

    private HTTPResponse delete(String url) {
        try {
            var req = new HTTPRequest(HTTPMethod.DELETE, url);
            req.headers.put("Authorization", "Bearer " + resolveToken());
            return httpClient.execute(req);
        } catch (Exception e) {
            throw new RuntimeException("Kubernetes API request failed: " + url, e);
        }
    }

    private boolean isPodReady(PodInfo pod) {
        if (pod.status == null) return false;
        if (!"Running".equals(pod.status.phase)) return false;
        // Check Ready condition
        if (pod.status.conditions != null) {
            for (var condition : pod.status.conditions) {
                if ("Ready".equals(condition.type) && "True".equals(condition.status)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static class PodInfo {
        @Property(name = "metadata")
        public PodMetadata metadata;
        @Property(name = "status")
        public PodStatus status;

        public String getName() {
            return metadata != null ? metadata.name : null;
        }

        public String getUid() {
            return metadata != null ? metadata.uid : null;
        }

        public String getIp() {
            return status != null ? status.podIP : null;
        }

        public String getPhase() {
            return status != null ? status.phase : null;
        }

        public Map<String, String> getLabels() {
            return metadata != null ? metadata.labels : null;
        }

        public static class PodMetadata {
            @Property(name = "name")
            public String name;
            @Property(name = "namespace")
            public String namespace;
            @Property(name = "uid")
            public String uid;
            @Property(name = "labels")
            public Map<String, String> labels;
            @Property(name = "creationTimestamp")
            public String creationTimestamp;
        }

        public static class PodStatus {
            @Property(name = "phase")
            public String phase;
            @Property(name = "podIP")
            public String podIP;
            @Property(name = "conditions")
            public Condition[] conditions;
        }

        public static class Condition {
            @Property(name = "type")
            public String type;
            @Property(name = "status")
            public String status;
        }
    }

    public static class PodList {
        @Property(name = "items")
        public List<PodInfo> items;
    }

    public static class ServiceInfo {
        @Property(name = "metadata")
        public ServiceMetadata metadata;
        @Property(name = "spec")
        public ServiceSpec spec;

        public static class ServiceMetadata {
            @Property(name = "name")
            public String name;
            @Property(name = "namespace")
            public String namespace;
            @Property(name = "uid")
            public String uid;
            @Property(name = "labels")
            public Map<String, String> labels;
        }

        public static class ServiceSpec {
            @Property(name = "type")
            public String type;
            @Property(name = "selector")
            public Map<String, String> selector;
            @Property(name = "ports")
            public Port[] ports;

            public static class Port {
                @Property(name = "port")
                public Integer port;
                @Property(name = "targetPort")
                public Integer targetPort;
                @Property(name = "nodePort")
                public Integer nodePort;
                @Property(name = "protocol")
                public String protocol;
            }
        }
    }

    public static class ServiceList {
        @Property(name = "items")
        public List<ServiceInfo> items;
    }
}
