package ai.core.server.blob;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AWS Signature V4 pre-signed URL generator for S3-compatible storage (MinIO, AWS S3, etc.).
 * No external SDK dependencies — pure Java crypto.
 *
 * @author stephen
 */
class MinioPresigner {
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
    private static final String ALGORITHM = "AWS4-HMAC-SHA256";
    private static final String SERVICE = "s3";
    private static final String TERMINATION = "aws4_request";
    private static final String UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD";

    private static byte[] hmac(byte[] key, String data) throws Exception {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(String data) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var hash = digest.digest(data.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("failed to compute SHA-256", e);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        var sb = new StringBuilder(bytes.length * 2);
        for (var b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String encodePath(String path) {
        return URLEncoder.encode(path, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String stripTrailingSlash(String s) {
        var result = s;
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }

    private final String endpoint;
    private final String region;
    private final byte[] secretKey;

    MinioPresigner(String endpoint, String region, String accessKey, String secretKey) {
        this.endpoint = stripTrailingSlash(endpoint);
        this.region = region != null && !region.isBlank() ? region : "us-east-1";
        this.secretKey = secretKey.getBytes(StandardCharsets.UTF_8);
    }

    PreSignedResult presignedPutUrl(String bucket, String key, int expirySeconds) {
        return presign("PUT", bucket, key, expirySeconds);
    }

    PreSignedResult presignedGetUrl(String bucket, String key, int expirySeconds) {
        return presign("GET", bucket, key, expirySeconds);
    }

    PreSignedResult presignedDeleteUrl(String bucket, String key, int expirySeconds) {
        return presign("DELETE", bucket, key, expirySeconds);
    }

    String url(String bucket, String key) {
        return endpoint + "/" + bucket + "/" + key;
    }

    private PreSignedResult presign(String method, String bucket, String key, int expirySeconds) {
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        var timestamp = TIMESTAMP_FORMAT.format(now);
        var date = DATE_FORMAT.format(now);
        var credential = buildCredential(date);
        var host = extractHost();

        var queryParams = new LinkedHashMap<String, String>();
        queryParams.put("X-Amz-Algorithm", ALGORITHM);
        queryParams.put("X-Amz-Credential", credential);
        queryParams.put("X-Amz-Date", timestamp);
        queryParams.put("X-Amz-Expires", String.valueOf(expirySeconds));
        queryParams.put("X-Amz-SignedHeaders", "host");

        var canonicalRequest = buildCanonicalRequest(method, bucket, key, queryParams, host);
        var stringToSign = buildStringToSign(timestamp, date, canonicalRequest);
        var signature = hmacSha256(date, stringToSign);

        queryParams.put("X-Amz-Signature", signature);

        var presignedUrl = endpoint + "/" + bucket + "/" + encodePath(key) + "?" + buildQuery(queryParams);
        var rawUrl = endpoint + "/" + bucket + "/" + encodePath(key);

        return new PreSignedResult(presignedUrl, rawUrl, bucket, key, timestamp);
    }

    private String buildCanonicalRequest(String method, String bucket, String key, Map<String, String> queryParams, String host) {
        var resourcePath = "/" + bucket + "/" + key;
        var canonicalQuery = buildCanonicalQuery(queryParams);
        var canonicalHeaders = "host:" + host + "\n";
        var signedHeaders = "host";

        return method + "\n"
                + resourcePath + "\n"
                + canonicalQuery + "\n"
                + canonicalHeaders + "\n"
                + signedHeaders + "\n"
                + UNSIGNED_PAYLOAD;
    }

    private String buildCanonicalQuery(Map<String, String> params) {
        var sb = new StringBuilder();
        for (var entry : params.entrySet()) {
            if (!sb.isEmpty()) sb.append('&');
            sb.append(urlEncode(entry.getKey()))
                    .append('=')
                    .append(urlEncode(entry.getValue()));
        }
        return sb.toString();
    }

    private String buildStringToSign(String timestamp, String date, String canonicalRequest) {
        return ALGORITHM + "\n"
                + timestamp + "\n"
                + date + "/" + region + "/" + SERVICE + "/" + TERMINATION + "\n"
                + sha256(canonicalRequest);
    }

    private String hmacSha256(String date, String data) {
        try {
            var dateKey = hmac(("AWS4" + new String(secretKey, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8), date);
            var regionKey = hmac(dateKey, region);
            var serviceKey = hmac(regionKey, SERVICE);
            var signingKey = hmac(serviceKey, TERMINATION);

            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            var hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("failed to compute AWS Signature V4", e);
        }
    }

    private String buildCredential(String date) {
        return date + "/" + region + "/" + SERVICE + "/" + TERMINATION;
    }

    private String extractHost() {
        var url = endpoint;
        if (url.startsWith("https://")) url = url.substring(8);
        else if (url.startsWith("http://")) url = url.substring(7);
        var portIdx = url.indexOf(':');
        if (portIdx > 0) return url.substring(0, portIdx);
        // Strip path if present
        var slashIdx = url.indexOf('/');
        if (slashIdx > 0) return url.substring(0, slashIdx);
        return url;
    }

    private String buildQuery(Map<String, String> params) {
        var sb = new StringBuilder();
        for (var entry : params.entrySet()) {
            if (!sb.isEmpty()) sb.append('&');
            sb.append(urlEncode(entry.getKey()))
                    .append('=')
                    .append(urlEncode(entry.getValue()));
        }
        return sb.toString();
    }

    record PreSignedResult(String presignedUrl, String rawUrl, String bucket, String key, String timestamp) {
    }
}
