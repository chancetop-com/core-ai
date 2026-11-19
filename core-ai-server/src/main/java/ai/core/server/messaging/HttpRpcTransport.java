package ai.core.server.messaging;

import ai.core.utils.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.regex.Pattern;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * Sends RPC directly to the owner pod. Addresses come from the pod registry and must be IPv4
 * literals: anything else is treated as "not delivered" without waiting for Kubernetes DNS.
 *
 * @author stephen
 */
public class HttpRpcTransport implements RpcTransport {
    private static final Logger LOGGER = LoggerFactory.getLogger(HttpRpcTransport.class);
    private static final Pattern IPV4 = Pattern.compile(
        "^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$");

    private final PodAddressResolver addressResolver;
    private final String token;
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .build();

    @SuppressFBWarnings("WEM_OBSCURING_EXCEPTION")
    public HttpRpcTransport(PodAddressResolver addressResolver, String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("sys.internal.token is required");
        }
        this.addressResolver = addressResolver;
        this.token = token;
    }

    @Override
    @SuppressFBWarnings({"EXS_EXCEPTION_SOFTENING_HAS_CHECKED", "WEM_OBSCURING_EXCEPTION"})
    public String call(String targetPod, SessionCommand command, Duration timeout) {
        var requestId = command.requestId();
        if (requestId == null) {
            throw new IllegalArgumentException("RPC command must have a non-null requestId");
        }
        var address = addressResolver.address(targetPod);
        if (address == null || address.isBlank()) {
            throw new RpcNotDeliveredException("no registered address for pod " + targetPod);
        }
        if (!isIpv4(address)) {
            throw new RpcNotDeliveredException("pod address is not an IP literal: " + address);
        }

        var request = request(address, targetPod, command, requestId, timeout);
        var started = System.nanoTime();
        try {
            return response(httpClient.send(request, HttpResponse.BodyHandlers.ofString()), targetPod, command, started);
        } catch (HttpConnectTimeoutException e) {
            throw new RpcNotDeliveredException("connect timeout, targetPod=" + targetPod, e);
        } catch (ConnectException e) {
            throw new RpcNotDeliveredException("connection refused, targetPod=" + targetPod, e);
        } catch (UnknownHostException e) {
            throw new RpcNotDeliveredException("unknown host, targetPod=" + targetPod, e);
        } catch (HttpTimeoutException e) {
            throw new RuntimeException("RPC HTTP timed out after " + timeout.toSeconds() + "s, type="
                + command.type() + ", sessionId=" + command.sessionId(), e);
        } catch (IOException e) {
            throw new RuntimeException("RPC HTTP failed, type=" + command.type() + ", sessionId="
                + command.sessionId() + ", targetPod=" + targetPod, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("RPC HTTP interrupted, type=" + command.type() + ", sessionId="
                + command.sessionId(), e);
        }
    }

    private HttpRequest request(String address, String targetPod, SessionCommand command, String requestId, Duration timeout) {
        var body = JsonUtil.toJson(new InternalRpcCommand(command.type().name(), command.sessionId(),
            command.userId(), command.payload(), requestId));
        return HttpRequest.newBuilder(URI.create("http://" + address + "/_internal/rpc"))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .header("X-Internal-Token", token)
            .header("X-Target-Pod", targetPod)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    }

    private String response(HttpResponse<String> response, String targetPod, SessionCommand command, long started) {
        var status = response.statusCode();
        if (status == 200) {
            LOGGER.debug("rpc http ok, targetPod={}, type={}, sessionId={}, elapsedMs={}",
                targetPod, command.type(), command.sessionId(), elapsedMillis(started));
            return response.body();
        }
        if (status == 404) {
            throw new RpcNotDeliveredException("rpc endpoint missing, targetPod=" + targetPod);
        }
        throw new RuntimeException("RPC HTTP error, status=" + status + ", type=" + command.type()
            + ", sessionId=" + command.sessionId() + ", targetPod=" + targetPod);
    }

    private boolean isIpv4(String address) {
        var separator = address.lastIndexOf(':');
        if (separator <= 0) return false;
        return IPV4.matcher(address.substring(0, separator)).matches();
    }

    private long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    @FunctionalInterface
    public interface PodAddressResolver {
        String address(String podId);
    }
}
