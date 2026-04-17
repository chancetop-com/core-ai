package ai.core.server.sandbox.agentsandbox;

import ai.core.server.sandbox.TokenResolver;
import core.framework.api.json.Property;
import core.framework.http.ContentType;
import core.framework.http.HTTPClient;
import core.framework.http.HTTPMethod;
import core.framework.http.HTTPRequest;
import core.framework.http.HTTPResponse;
import core.framework.json.JSON;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * @author stephen
 */
public class AgentSandboxClient {
    private static final String API_GROUP = "agents.x-k8s.io";
    private static final String API_VERSION = "v1alpha1";
    private final String apiServer;
    private final String namespace;
    private final HTTPClient httpClient;
    private final TokenResolver tokenResolver;

    public AgentSandboxClient(String apiServer, String namespace, TokenResolver tokenResolver, int timeoutSeconds) {
        this.apiServer = apiServer.trim().replaceAll("/+$", "");
        this.namespace = namespace;
        this.tokenResolver = tokenResolver;
        this.httpClient = HTTPClient.builder().trustAll().connectTimeout(Duration.ofSeconds(10)).timeout(Duration.ofSeconds(timeoutSeconds)).build();
    }

    private String baseUrl() {
        return apiServer + "/apis/" + API_GROUP + "/" + API_VERSION + "/namespaces/" + namespace;
    }

    public SandboxCR createSandbox(String crJson) {
        var url = baseUrl() + "/sandboxes";
        var response = post(url, crJson);
        if (response.statusCode != 201) {
            throw new RuntimeException("Failed to create Sandbox CR: " + response.statusCode + " " + response.text());
        }
        return JSON.fromJSON(SandboxCR.class, response.text());
    }

    public Optional<SandboxCR> getSandbox(String name) {
        var url = baseUrl() + "/sandboxes/" + name;
        var response = get(url);
        if (response.statusCode == 404) {
            return Optional.empty();
        }
        if (response.statusCode != 200) {
            throw new RuntimeException("Failed to get Sandbox CR: " + response.statusCode + " " + response.text());
        }
        return Optional.of(JSON.fromJSON(SandboxCR.class, response.text()));
    }

    public void deleteSandbox(String name) {
        var url = baseUrl() + "/sandboxes/" + name;
        var response = delete(url);
        if (response.statusCode != 200 && response.statusCode != 202 && response.statusCode != 404) {
            throw new RuntimeException("Failed to delete Sandbox CR: " + response.statusCode + " " + response.text());
        }
    }

    public List<SandboxCR> listSandboxes(String labelSelector) {
        var url = baseUrl() + "/sandboxes?labelSelector=" + labelSelector;
        var response = get(url);
        if (response.statusCode != 200) {
            throw new RuntimeException("Failed to list Sandbox CRs: " + response.statusCode + " " + response.text());
        }
        var list = JSON.fromJSON(SandboxCRList.class, response.text());
        return list.items != null ? list.items : List.of();
    }

    public SandboxCR waitForReady(String name, int maxWaitMs) {
        var startTime = System.currentTimeMillis();
        while (System.currentTimeMillis() - startTime < maxWaitMs) {
            var opt = getSandbox(name);
            if (opt.isEmpty()) {
                throw new RuntimeException("Sandbox CR not found: " + name);
            }
            var cr = opt.get();
            if (cr.status != null) {
                if (isReady(cr)) {
                    return cr;
                }
                if (isFailed(cr)) {
                    throw new RuntimeException("Sandbox CR failed: " + name);
                }
            }
            sleep(2000);
        }
        throw new RuntimeException("Sandbox CR readiness timeout: " + name);
    }

    private boolean isReady(SandboxCR cr) {
        if (cr.status == null) {
            return false;
        }
        if (cr.status.serviceFQDN != null && !cr.status.serviceFQDN.isBlank()) {
            return true;
        }
        if (cr.status.podIPs != null && cr.status.podIPs.length > 0) {
            return true;
        }
        if (cr.status.conditions != null) {
            for (var c : cr.status.conditions) {
                if ("Ready".equals(c.type) && "True".equals(c.status)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isFailed(SandboxCR cr) {
        if (cr.status == null || cr.status.conditions == null) {
            return false;
        }
        for (var c : cr.status.conditions) {
            if ("Failed".equals(c.type) && "True".equals(c.status)) {
                return true;
            }
        }
        return false;
    }

    private HTTPResponse get(String url) {
        try {
            var req = new HTTPRequest(HTTPMethod.GET, url);
            req.headers.put("Authorization", "Bearer " + tokenResolver.resolve());
            return httpClient.execute(req);
        } catch (Exception e) {
            throw new RuntimeException("Agent Sandbox API request failed: " + url, e);
        }
    }

    private HTTPResponse post(String url, String body) {
        try {
            var req = new HTTPRequest(HTTPMethod.POST, url);
            req.headers.put("Authorization", "Bearer " + tokenResolver.resolve());
            req.body(body, ContentType.APPLICATION_JSON);
            return httpClient.execute(req);
        } catch (Exception e) {
            throw new RuntimeException("Agent Sandbox API request failed: " + url, e);
        }
    }

    private HTTPResponse delete(String url) {
        try {
            var req = new HTTPRequest(HTTPMethod.DELETE, url);
            req.headers.put("Authorization", "Bearer " + tokenResolver.resolve());
            return httpClient.execute(req);
        } catch (Exception e) {
            throw new RuntimeException("Agent Sandbox API request failed: " + url, e);
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static class SandboxCR {
        @Property(name = "metadata")
        public Metadata metadata;
        @Property(name = "spec")
        public SandboxSpec spec;
        @Property(name = "status")
        public SandboxStatus status;

        public String getName() {
            return metadata != null ? metadata.name : null;
        }
    }

    public static class SandboxCRList {
        @Property(name = "items")
        public List<SandboxCR> items;
    }

    public static class Metadata {
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

    public static class SandboxSpec {
        @Property(name = "replicas")
        public Integer replicas;
        @Property(name = "shutdownPolicy")
        public String shutdownPolicy;
        @Property(name = "shutdownTime")
        public String shutdownTime;
        @Property(name = "podTemplate")
        public PodTemplate podTemplate;
    }

    public static class PodTemplate {
        @Property(name = "metadata")
        public Map<String, Object> metadata;
        @Property(name = "spec")
        public Map<String, Object> spec;
    }

    public static class SandboxStatus {
        @Property(name = "conditions")
        public Condition[] conditions;
        @Property(name = "podIPs")
        public String[] podIPs;
        @Property(name = "replicas")
        public Integer replicas;
        @Property(name = "selector")
        public String selector;
        @Property(name = "service")
        public String service;
        @Property(name = "serviceFQDN")
        public String serviceFQDN;
    }

    public static class Condition {
        @Property(name = "type")
        public String type;
        @Property(name = "status")
        public String status;
        @Property(name = "message")
        public String message;
    }
}
