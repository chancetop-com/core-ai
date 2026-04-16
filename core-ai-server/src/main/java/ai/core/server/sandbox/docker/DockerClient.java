package ai.core.server.sandbox.docker;

import core.framework.api.json.Property;
import core.framework.http.ContentType;
import core.framework.http.HTTPClient;
import core.framework.http.HTTPMethod;
import core.framework.http.HTTPRequest;
import core.framework.http.HTTPResponse;
import core.framework.json.JSON;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * @author stephen
 */
public class DockerClient {
    private static final String DOCKER_API_VERSION = "/v1.43";
    private static final int RUNTIME_PORT = 8080;

    private final String socketPath;
    private final Path workspaceBase;
    private final HTTPClient httpClient;

    public DockerClient(String socketPath, Path workspaceBase) {
        this.socketPath = socketPath;
        this.workspaceBase = workspaceBase;
        this.httpClient = HTTPClient.builder().build();
    }

    public String createContainer(Map<String, Object> request) {
        var response = post("/containers/create", request);
        if (response.statusCode == 404) {
            throw new RuntimeException("Image not found: " + request.get("Image"));
        }
        if (response.statusCode != 200 && response.statusCode != 201) {
            throw new RuntimeException("Failed to create container: " + response.statusCode + " " + response.text());
        }
        var result = JSON.fromJSON(ContainerCreateResponse.class, response.text());
        return result.id;
    }

    public void startContainer(String containerId) {
        var response = post("/containers/" + containerId + "/start", null);
        if (response.statusCode != 204 && response.statusCode != 304) {
            throw new RuntimeException("Failed to start container: " + response.statusCode + " " + response.text());
        }
    }

    public Optional<ContainerInfo> getContainer(String containerId) {
        var response = get("/containers/" + containerId + "/json");
        if (response.statusCode == 404) {
            return Optional.empty();
        }
        if (response.statusCode != 200) {
            throw new RuntimeException("Failed to get container: " + response.statusCode);
        }
        return Optional.of(JSON.fromJSON(ContainerInfo.class, response.text()));
    }

    public String waitForRunning(String containerId) {
        var startTime = System.currentTimeMillis();
        var maxWait = 60_000;
        var pollInterval = 500;

        while (System.currentTimeMillis() - startTime < maxWait) {
            var containerOpt = getContainer(containerId);
            if (containerOpt.isPresent()) {
                var container = containerOpt.get();
                var state = container.state != null ? container.state.status : null;
                if ("running".equals(state)) {
                    var hostPort = getMappedHostPort(container);
                    if (hostPort != null) {
                        return "127.0.0.1:" + hostPort;
                    }
                    // Fallback to container IP (Linux)
                    var ip = getContainerIP(container);
                    if (ip != null && !ip.isBlank()) {
                        return ip + ":" + RUNTIME_PORT;
                    }
                }
                if ("exited".equals(state)) {
                    throw new RuntimeException("Container exited unexpectedly: " + containerId);
                }
            }
            sleep(pollInterval);
        }
        throw new RuntimeException("Container readiness timeout: " + containerId);
    }

    public void removeContainer(String containerId) {
        // Force stop and remove
        var response = delete("/containers/" + containerId + "?force=true");
        if (response.statusCode != 204 && response.statusCode != 404) {
            throw new RuntimeException("Failed to remove container: " + response.statusCode + " " + response.text());
        }
    }

    public String getLogs(String containerId, int tail) {
        var response = get("/containers/" + containerId + "/logs?stdout=true&stderr=true&tail=" + tail);
        if (response.statusCode != 200) {
            return "Failed to get logs: " + response.statusCode;
        }
        return response.text();
    }

    private String getMappedHostPort(ContainerInfo container) {
        if (container.networkSettings != null && container.networkSettings.ports != null) {
            var bindings = container.networkSettings.ports.get(RUNTIME_PORT + "/tcp");
            if (bindings != null && !bindings.isEmpty()) {
                return bindings.getFirst().hostPort;
            }
        }
        return null;
    }

    private String getContainerIP(ContainerInfo container) {
        if (container.networkSettings != null && container.networkSettings.networks != null) {
            // Try to find an IP in the networks
            for (var entry : container.networkSettings.networks.entrySet()) {
                var network = entry.getValue();
                if (network.ipAddress != null && !network.ipAddress.isBlank()) {
                    return network.ipAddress;
                }
            }
        }
        return null;
    }

    private HTTPResponse get(String path) {
        try {
            var uri = buildUri(path);
            var req = new HTTPRequest(HTTPMethod.GET, uri);
            req.contentType = ContentType.APPLICATION_JSON;
            return httpClient.execute(req);
        } catch (Exception e) {
            throw new RuntimeException("Docker API request failed: " + path, e);
        }
    }

    private HTTPResponse post(String path, Map<String, Object> body) {
        try {
            var uri = buildUri(path);
            var content = body != null ? JSON.toJSON(body) : "";
            var req = new HTTPRequest(HTTPMethod.POST, uri);
            req.contentType = ContentType.APPLICATION_JSON;
            req.body(content, ContentType.APPLICATION_JSON);
            return httpClient.execute(req);
        } catch (Exception e) {
            throw new RuntimeException("Docker API request failed: " + path, e);
        }
    }

    private HTTPResponse delete(String path) {
        try {
            var uri = buildUri(path);
            var req = new HTTPRequest(HTTPMethod.DELETE, uri);
            req.contentType = ContentType.APPLICATION_JSON;
            return httpClient.execute(req);
        } catch (Exception e) {
            throw new RuntimeException("Docker API request failed: " + path, e);
        }
    }

    private String buildUri(String path) {
        if (socketPath.startsWith("tcp://")) {
            return "http://" + socketPath.substring("tcp://".length()) + DOCKER_API_VERSION + path;
        }
        // Default: assume Docker Desktop TCP endpoint on localhost:2375
        return "http://localhost:2375" + DOCKER_API_VERSION + path;
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public Path workspaceBase() {
        return workspaceBase;
    }

    public static class ContainerCreateResponse {
        @Property(name = "Id")
        public String id;
        @Property(name = "Warnings")
        public List<String> warnings;
    }

    public static class ContainerInfo {
        @Property(name = "Id")
        public String id;
        @Property(name = "Name")
        public String name;
        @Property(name = "State")
        public ContainerState state;
        @Property(name = "NetworkSettings")
        public NetworkSettings networkSettings;

        public static class ContainerState {
            @Property(name = "Status")
            public String status;
            @Property(name = "Running")
            public Boolean running;
        }

        public static class NetworkSettings {
            @Property(name = "Networks")
            public Map<String, Network> networks;
            @Property(name = "IPAddress")
            public String ipAddress;
            @Property(name = "Ports")
            public Map<String, List<PortBinding>> ports;
        }

        public static class PortBinding {
            @Property(name = "HostIp")
            public String hostIp;
            @Property(name = "HostPort")
            public String hostPort;
        }

        public static class Network {
            @Property(name = "IPAddress")
            public String ipAddress;
            @Property(name = "Gateway")
            public String gateway;
        }
    }
}
