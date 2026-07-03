package ai.core.server.gateway;

import core.framework.web.exception.BadRequestException;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

final class GatewayNetworkGuard {
    static void validateHttpUrlSyntax(String url) {
        var uri = parse(url);
        var scheme = uri.getScheme();
        if (scheme == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw new BadRequestException("baseUrl must use http or https");
        }
        var host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new BadRequestException("baseUrl must include a host");
        }
    }

    static void validateOutboundUrl(String url, boolean allowPrivateNetwork) {
        var uri = parse(url);
        validateHttpUrlSyntax(url);
        if (allowPrivateNetwork) return;
        var host = uri.getHost();
        try {
            for (var address : InetAddress.getAllByName(host)) {
                if (isBlocked(address)) {
                    throw new BadRequestException("gateway provider URL resolves to a private or local address: " + address.getHostAddress());
                }
            }
        } catch (UnknownHostException e) {
            throw new BadRequestException("gateway provider host cannot be resolved: " + host);
        }
    }

    private static URI parse(String url) {
        try {
            return URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("invalid gateway provider URL: " + e.getMessage());
        }
    }

    private static boolean isBlocked(InetAddress address) {
        return address.isLoopbackAddress() || address.isAnyLocalAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress();
    }

    private GatewayNetworkGuard() {
    }
}
