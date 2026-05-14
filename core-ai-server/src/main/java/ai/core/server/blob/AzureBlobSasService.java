package ai.core.server.blob;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * @author stephen
 */
public class AzureBlobSasService {
    private static final String SAS_VERSION = "2018-11-09";
    private static final DateTimeFormatter EXPIRY_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");

    private final String accountName;
    private final byte[] accountKey;

    public AzureBlobSasService(String accountName, String accountKey) {
        this.accountName = accountName;
        this.accountKey = Base64.getDecoder().decode(accountKey);
    }

    public SasResult generateContainerSas(String containerName, String blobName, int expiryMinutes) {
        var expiry = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(expiryMinutes);
        var expiryStr = EXPIRY_FORMATTER.format(expiry);

        var params = new LinkedHashMap<String, String>();
        params.put("sv", SAS_VERSION);
        params.put("spr", "https");
        params.put("st", EXPIRY_FORMATTER.format(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5)));
        params.put("se", expiryStr);
        params.put("sr", "c");
        params.put("sp", "cw");  // create + write

        var stringToSign = buildStringToSign(containerName, params);
        var signature = hmacSha256(stringToSign);
        params.put("sig", signature);

        var sasToken = buildQueryString(params);

        var blobPath = containerName + "/" + blobName;
        var uploadUrl = "https://" + accountName + ".blob.core.windows.net/" + blobPath + "?" + sasToken;
        var blobUrl = "https://" + accountName + ".blob.core.windows.net/" + blobPath;

        return new SasResult(uploadUrl, blobUrl, containerName, blobName, expiryStr);
    }

    // String-to-sign format per Azure REST API spec, version 2018-11-09:
    // signedPermissions + "\n" + signedStart + "\n" + signedExpiry + "\n" +
    // canonicalizedResource + "\n" + signedIdentifier + "\n" +
    // signedIP + "\n" + signedProtocol + "\n" + signedVersion + "\n" +
    // signedResource + "\n" + signedSnapshotTime + "\n" +
    // rscc + "\n" + rscd + "\n" + rsce + "\n" + rscl + "\n" + rsct
    private String buildStringToSign(String containerName, Map<String, String> params) {
        var canonicalized = "/blob/" + accountName + "/" + containerName;
        var sb = new StringBuilder();
        sb.append(params.getOrDefault("sp", "")).append('\n');
        sb.append(params.getOrDefault("st", "")).append('\n');
        sb.append(params.getOrDefault("se", "")).append('\n');
        sb.append(canonicalized).append('\n');
        sb.append(params.getOrDefault("si", "")).append('\n');
        sb.append(params.getOrDefault("sip", "")).append('\n');
        sb.append(params.getOrDefault("spr", "")).append('\n');
        sb.append(params.getOrDefault("sv", "")).append('\n');
        sb.append(params.getOrDefault("sr", "")).append('\n');  // signedResource
        sb.append('\n');  // signedSnapshotTime
        sb.append('\n');  // rscc
        sb.append('\n');  // rscd
        sb.append('\n');  // rsce
        sb.append('\n');  // rscl
        sb.append("");    // rsct
        return sb.toString();
    }

    private String hmacSha256(String data) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(accountKey, "HmacSHA256"));
            var hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute HMAC-SHA256 for SAS token", e);
        }
    }

    private String buildQueryString(Map<String, String> params) {
        var sb = new StringBuilder();
        for (var entry : params.entrySet()) {
            if (!sb.isEmpty()) sb.append('&');
            sb.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
            sb.append('=');
            sb.append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    public record SasResult(String uploadUrl, String blobUrl, String container, String blobName, String expiresAt) {}
}
