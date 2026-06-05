package ai.core.server.blob;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private static final Logger LOGGER = LoggerFactory.getLogger(AzureBlobSasService.class);
    private static final String SAS_VERSION = "2018-11-09";
    private static final DateTimeFormatter EXPIRY_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");

    private final String accountName;
    private final byte[] accountKey;

    public AzureBlobSasService(String accountName, String accountKey) {
        this.accountName = accountName;
        this.accountKey = Base64.getDecoder().decode(accountKey);
    }

    public static AzureBlobSasService tryCreate(String accountName, String accountKey) {
        if (accountName == null || accountName.isBlank() || accountKey == null || accountKey.isBlank()) {
            return null;
        }
        try {
            return new AzureBlobSasService(accountName, accountKey);
        } catch (IllegalArgumentException e) {
            LOGGER.warn("Azure Blob Storage account key is not valid base64, blob upload will be unavailable", e);
            return null;
        }
    }

    public SasResult generateContainerSas(String containerName, String blobName, int expiryMinutes) {
        var expiry = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(expiryMinutes);
        var expiryStr = EXPIRY_FORMATTER.format(expiry);
        var startStr = EXPIRY_FORMATTER.format(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5));

        var params = new LinkedHashMap<String, String>();
        params.put("sv", SAS_VERSION);
        params.put("spr", "https");
        params.put("st", startStr);
        params.put("se", expiryStr);
        params.put("sr", "c");
        params.put("sp", "cw");  // create + write

        return buildResult(containerName, blobName, params, expiryStr);
    }

    public SasResult generateReadBlobSas(String containerName, String blobName, int expiryMinutes) {
        var expiry = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(expiryMinutes);
        var expiryStr = EXPIRY_FORMATTER.format(expiry);
        var startStr = EXPIRY_FORMATTER.format(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5));

        var params = new LinkedHashMap<String, String>();
        params.put("sv", SAS_VERSION);
        params.put("spr", "https");
        params.put("st", startStr);
        params.put("se", expiryStr);
        params.put("sr", "b");  // blob-level (not container)
        params.put("sp", "r");  // read only

        return buildResult(containerName, blobName, params, expiryStr);
    }

    private SasResult buildResult(String containerName, String blobName, Map<String, String> params, String expiryStr) {
        var stringToSign = buildStringToSign(accountName, containerName, blobName, params);
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
    @SuppressWarnings({"PMD.ConsecutiveLiteralAppends", "PMD.ConsecutiveAppendsShouldReuse"})
    private String buildStringToSign(String accountName, String containerName, String blobName, Map<String, String> params) {
        var resource = buildCanonicalizedResource(containerName, blobName, params.get("sr"));
        var canonicalized = "/blob/" + accountName + "/" + resource;
        var sb = new StringBuilder();
        sb.append(params.getOrDefault("sp", ""))
            .append('\n')
            .append(params.getOrDefault("st", ""))
            .append('\n')
            .append(params.getOrDefault("se", ""))
            .append('\n')
            .append(canonicalized)
            .append('\n')
            .append(params.getOrDefault("si", ""))
            .append('\n')
            .append(params.getOrDefault("sip", ""))
            .append('\n')
            .append(params.getOrDefault("spr", ""))
            .append('\n')
            .append(params.getOrDefault("sv", ""))
            .append('\n')
            .append(params.getOrDefault("sr", ""))
            .append('\n');
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

    private static String buildCanonicalizedResource(String containerName, String blobName, String signedResource) {
        if ("b".equals(signedResource) && blobName != null) {
            return containerName + "/" + blobName;
        }
        return containerName;
    }

    private String buildQueryString(Map<String, String> params) {
        var sb = new StringBuilder();
        for (var entry : params.entrySet()) {
            if (!sb.isEmpty()) sb.append('&');
            sb.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                .append('=')
                .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    public record SasResult(String uploadUrl, String blobUrl, String container, String blobName, String expiresAt) { }
}
