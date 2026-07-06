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
                || address.isSiteLocalAddress() || address.isMulticastAddress()
                || isUniqueLocalIpv6(address) || isCarrierGradeNat(address);
    }

    // fc00::/7 unique local IPv6, not covered by isSiteLocalAddress (which only matches deprecated fec0::/10)
    private static boolean isUniqueLocalIpv6(InetAddress address) {
        var bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
    }

    // 100.64.0.0/10 carrier-grade NAT range
    private static boolean isCarrierGradeNat(InetAddress address) {
        var bytes = address.getAddress();
        return bytes.length == 4 && (bytes[0] & 0xFF) == 100 && (bytes[1] & 0xC0) == 64;
    }

    private GatewayNetworkGuard() {
    }
}
