package ai.core.server.sandbox.kubernetes;

import core.framework.api.json.Property;
import core.framework.http.ContentType;
import core.framework.http.HTTPClient;
import core.framework.http.HTTPMethod;
import core.framework.http.HTTPRequest;
import core.framework.http.HTTPResponse;
import core.framework.json.JSON;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * @author stephen
 */
public class KubernetesClient {
    private final String apiServer;
    private final String token;
    private final String namespace;
    private final HTTPClient httpClient;

    public KubernetesClient(String apiServer, String token, String namespace, int timeoutSeconds) {
        this.apiServer = apiServer.trim().replaceAll("/+$", "");
        this.token = token;
        this.namespace = namespace;
        this.httpClient = HTTPClient.builder()
                .connectTimeout(Duration.ofSeconds(10))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .build();
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
            req.headers.put("Authorization", "Bearer " + token);
            return httpClient.execute(req);
        } catch (Exception e) {
            throw new RuntimeException("Kubernetes API request failed: " + url, e);
        }
    }

    private HTTPResponse post(String url, String body) {
        try {
            var req = new HTTPRequest(HTTPMethod.POST, url);
            req.headers.put("Authorization", "Bearer " + token);
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
            req.headers.put("Authorization", "Bearer " + token);
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
}
