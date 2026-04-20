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
public class AgentSandboxExtensionsClient {
    private static final String API_GROUP = "extensions.agents.x-k8s.io";
    private static final String API_VERSION = "v1alpha1";

    private final String apiServer;
    private final String namespace;
    private final HTTPClient httpClient;
    private final TokenResolver tokenResolver;

    public AgentSandboxExtensionsClient(String apiServer, String namespace, TokenResolver tokenResolver, int timeoutSeconds) {
        this.apiServer = apiServer.trim().replaceAll("/+$", "");
        this.namespace = namespace;
        this.tokenResolver = tokenResolver;
        this.httpClient = HTTPClient.builder()
                .trustAll()
                .connectTimeout(Duration.ofSeconds(10))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .build();
    }

    private String baseUrl() {
        return apiServer + "/apis/" + API_GROUP + "/" + API_VERSION + "/namespaces/" + namespace;
    }

    // SandboxClaim operations

    public SandboxClaim createClaim(String claimJson) {
        var url = baseUrl() + "/sandboxclaims";
        var response = post(url, claimJson);
        if (response.statusCode != 201) {
            throw new RuntimeException("Failed to create SandboxClaim: " + response.statusCode + " " + response.text());
        }
        return JSON.fromJSON(SandboxClaim.class, response.text());
    }

    public Optional<SandboxClaim> getClaim(String name) {
        var url = baseUrl() + "/sandboxclaims/" + name;
        var response = get(url);
        if (response.statusCode == 404) return Optional.empty();
        if (response.statusCode != 200) {
            throw new RuntimeException("Failed to get SandboxClaim: " + response.statusCode + " " + response.text());
        }
        return Optional.of(JSON.fromJSON(SandboxClaim.class, response.text()));
    }

    public void deleteClaim(String name) {
        var url = baseUrl() + "/sandboxclaims/" + name;
        var response = delete(url);
        if (response.statusCode != 200 && response.statusCode != 202 && response.statusCode != 404) {
            throw new RuntimeException("Failed to delete SandboxClaim: " + response.statusCode + " " + response.text());
        }
    }

    public List<SandboxClaim> listClaims(String labelSelector) {
        var url = baseUrl() + "/sandboxclaims?labelSelector=" + labelSelector;
        var response = get(url);
        if (response.statusCode != 200) {
            throw new RuntimeException("Failed to list SandboxClaims: " + response.statusCode + " " + response.text());
        }
        var list = JSON.fromJSON(SandboxClaimList.class, response.text());
        return list.items != null ? list.items : List.of();
    }

    public SandboxClaim waitForReady(String name, int maxWaitMs) {
        var startTime = System.currentTimeMillis();
        while (System.currentTimeMillis() - startTime < maxWaitMs) {
            var opt = getClaim(name);
            if (opt.isEmpty()) throw new RuntimeException("SandboxClaim not found: " + name);
            var claim = opt.get();
            if (claim.status != null) {
                if (isReady(claim)) return claim;
                if (isFailed(claim)) throw new RuntimeException("SandboxClaim failed: " + name);
            }
            sleep(1000);
        }
        throw new RuntimeException("SandboxClaim readiness timeout: " + name);
    }

    private boolean isReady(SandboxClaim claim) {
        if (claim.status == null) return false;
        if (claim.status.sandbox != null && claim.status.sandbox.podIPs != null && claim.status.sandbox.podIPs.length > 0) {
            return true;
        }
        if (claim.status.conditions != null) {
            for (var c : claim.status.conditions) {
                if ("Ready".equals(c.type) && "True".equals(c.status)) return true;
            }
        }
        return false;
    }

    private boolean isFailed(SandboxClaim claim) {
        if (claim.status == null || claim.status.conditions == null) return false;
        for (var c : claim.status.conditions) {
            if ("Failed".equals(c.type) && "True".equals(c.status)) return true;
        }
        return false;
    }

    private HTTPResponse get(String url) {
        try {
            var req = new HTTPRequest(HTTPMethod.GET, url);
            req.headers.put("Authorization", "Bearer " + tokenResolver.resolve());
            return httpClient.execute(req);
        } catch (Exception e) {
            throw new RuntimeException("Agent Sandbox Extensions API request failed: " + url, e);
        }
    }

    private HTTPResponse post(String url, String body) {
        try {
            var req = new HTTPRequest(HTTPMethod.POST, url);
            req.headers.put("Authorization", "Bearer " + tokenResolver.resolve());
            req.body(body, ContentType.APPLICATION_JSON);
            return httpClient.execute(req);
        } catch (Exception e) {
            throw new RuntimeException("Agent Sandbox Extensions API request failed: " + url, e);
        }
    }

    private HTTPResponse delete(String url) {
        try {
            var req = new HTTPRequest(HTTPMethod.DELETE, url);
            req.headers.put("Authorization", "Bearer " + tokenResolver.resolve());
            return httpClient.execute(req);
        } catch (Exception e) {
            throw new RuntimeException("Agent Sandbox Extensions API request failed: " + url, e);
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // Model classes

    public static class SandboxClaim {
        @Property(name = "metadata") public Metadata metadata;
        @Property(name = "spec") public SandboxClaimSpec spec;
        @Property(name = "status") public SandboxClaimStatus status;

        public String getName() {
            return metadata != null ? metadata.name : null;
        }
    }

    public static class SandboxClaimList {
        @Property(name = "items") public List<SandboxClaim> items;
    }

    public static class Metadata {
        @Property(name = "name") public String name;
        @Property(name = "namespace") public String namespace;
        @Property(name = "uid") public String uid;
        @Property(name = "labels") public Map<String, String> labels;
        @Property(name = "creationTimestamp") public String creationTimestamp;
    }

    public static class SandboxClaimSpec {
        @Property(name = "sandboxTemplateRef") public SandboxTemplateRef sandboxTemplateRef;
        @Property(name = "warmpool") public String warmpool;
        @Property(name = "lifecycle") public Lifecycle lifecycle;
    }

    public static class SandboxTemplateRef {
        @Property(name = "name") public String name;
    }

    public static class Lifecycle {
        @Property(name = "shutdownPolicy") public String shutdownPolicy;
        @Property(name = "shutdownTime") public String shutdownTime;
    }

    public static class SandboxClaimStatus {
        @Property(name = "conditions") public Condition[] conditions;
        @Property(name = "sandbox") public SandboxRef sandbox;
    }

    public static class SandboxRef {
        @Property(name = "name") public String name;
        @Property(name = "podIPs") public String[] podIPs;
    }

    public static class Condition {
        @Property(name = "type") public String type;
        @Property(name = "status") public String status;
        @Property(name = "message") public String message;
    }
}

