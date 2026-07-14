package ai.core.server.github;

import ai.core.tool.github.GitHubTokenProvider;
import ai.core.tool.github.GitHubTokenProviderRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * @author stephen
 */
public class GitHubInstallationTokenService implements GitHubTokenProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(GitHubInstallationTokenService.class);

    private static final String GITHUB_API_BASE = "https://api.github.com";
    private static final int TOKEN_TTL_SECONDS = 600; // JWT max 10 minutes per GitHub spec
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(15);

    // ==================== Static helpers ====================

    private static PrivateKey parsePrivateKey(String pem) {
        try {
            return parsePkcs8(pem);
        } catch (InvalidKeySpecException e) {
            // Not PKCS8 — GitHub generates PKCS1 keys by default
            return parsePkcs1PrivateKey(pem);
        }
    }

    private static PrivateKey parsePkcs8(String pem) throws InvalidKeySpecException {
        try {
            var key = stripPem(pem);
            var decoded = Base64.getDecoder().decode(key);
            var keySpec = new PKCS8EncodedKeySpec(decoded);
            var keyFactory = KeyFactory.getInstance("RSA");
            return keyFactory.generatePrivate(keySpec);
        } catch (InvalidKeySpecException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse GitHub App private key as PKCS8", e);
        }
    }

    @SuppressFBWarnings("SR_NOT_CHECKED")
    private static PrivateKey parsePkcs1PrivateKey(String pem) {
        try {
            var key = stripPem(pem);
            var decoded = Base64.getDecoder().decode(key);

            // PKCS#1 RSA private key DER structure parsing
            var dis = new java.io.DataInputStream(new java.io.ByteArrayInputStream(decoded));
            // Read SEQUENCE tag
            if (dis.readByte() != 0x30) throw new RuntimeException("Expected DER SEQUENCE");
            readDerLength(dis); // skip total length

            // Read version INTEGER
            if (dis.readByte() != 0x02) throw new RuntimeException("Expected DER INTEGER (version)");
            int verLen = readDerLength(dis);
            dis.skipBytes(verLen);

            // Read modulus INTEGER
            if (dis.readByte() != 0x02) throw new RuntimeException("Expected DER INTEGER (modulus)");
            int modLen = readDerLength(dis);
            var modulus = new byte[modLen];
            dis.readFully(modulus);

            // Read public exponent INTEGER
            if (dis.readByte() != 0x02) throw new RuntimeException("Expected DER INTEGER (publicExponent)");
            int pubExpLen = readDerLength(dis);
            dis.skipBytes(pubExpLen);

            // Read private exponent INTEGER
            if (dis.readByte() != 0x02) throw new RuntimeException("Expected DER INTEGER (privateExponent)");
            int privExpLen = readDerLength(dis);
            var privateExponent = new byte[privExpLen];
            dis.readFully(privateExponent);

            var modulusBigInt = new java.math.BigInteger(1, modulus);
            var privateExponentBigInt = new java.math.BigInteger(1, privateExponent);

            var keySpec = new java.security.spec.RSAPrivateKeySpec(modulusBigInt, privateExponentBigInt);
            return KeyFactory.getInstance("RSA").generatePrivate(keySpec);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse GitHub App private key as PKCS1", e);
        }
    }

    private static int readDerLength(java.io.DataInputStream dis) throws java.io.IOException {
        int b = dis.readUnsignedByte();
        if (b < 0x80) return b;
        int numBytes = b & 0x7F;
        int length = 0;
        for (int i = 0; i < numBytes; i++) {
            length = (length << 8) | dis.readUnsignedByte();
        }
        return length;
    }

    private static String stripPem(String pem) {
        return pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                .replace("-----END RSA PRIVATE KEY-----", "")
                .replaceAll("\\s+", "");
    }

    private final HttpClient httpClient;
    private final String appId;
    private final PrivateKey privateKey;
    private final Long installationId;

    // Cache installation IDs per repo when auto-discovering (only used if installationId not configured)
    private final Map<String, Long> installationCache = new ConcurrentHashMap<>();

    public GitHubInstallationTokenService(String appId, String privateKeyPem, Long installationId) {
        this.appId = appId;
        this.privateKey = parsePrivateKey(privateKeyPem);
        this.installationId = installationId;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(HTTP_TIMEOUT)
                .build();
    }

    // ==================== Instance methods ====================

    public void register() {
        GitHubTokenProviderRegistry.setProvider(this);
        LOGGER.info("GitHub installation token service registered (appId={}, installationId={})", appId, installationId);
    }

    @Override
    public String getInstallationToken(String repoFullName) {
        try {
            long instId = installationId != null ? installationId : resolveInstallationId(repoFullName);
            return createInstallationToken(instId);
        } catch (Exception e) {
            throw new RuntimeException("Failed to get GitHub installation token for " + repoFullName, e);
        }
    }

    private long resolveInstallationId(String repoFullName) throws Exception {
        var cached = installationCache.get(repoFullName);
        if (cached != null) return cached;

        var jwt = createJwt();
        var request = HttpRequest.newBuilder()
                .uri(URI.create(GITHUB_API_BASE + "/repos/" + repoFullName + "/installation"))
                .header("Authorization", "Bearer " + jwt)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "core-ai-server")
                .timeout(HTTP_TIMEOUT)
                .GET()
                .build();

        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new RuntimeException("Failed to resolve installation for " + repoFullName
                    + ": HTTP " + response.statusCode() + " - " + response.body());
        }

        var body = response.body();
        var idMarker = "\"id\":";
        var idIdx = body.indexOf(idMarker);
        if (idIdx < 0) {
            throw new RuntimeException("No installation id found in response for " + repoFullName);
        }
        var idStart = idIdx + idMarker.length();
        var idEnd = body.indexOf(',', idStart);
        if (idEnd < 0) idEnd = body.indexOf('}', idStart);
        var idStr = body.substring(idStart, idEnd).trim();
        long installationId = Long.parseLong(idStr);

        installationCache.put(repoFullName, installationId);
        LOGGER.info("Resolved installation {} for repo {}", installationId, repoFullName);
        return installationId;
    }

    private String createInstallationToken(long installationId) throws Exception {
        var jwt = createJwt();

        var request = HttpRequest.newBuilder()
                .uri(URI.create(GITHUB_API_BASE + "/app/installations/" + installationId + "/access_tokens"))
                .header("Authorization", "Bearer " + jwt)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "core-ai-server")
                .timeout(HTTP_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 201) {
            throw new RuntimeException("Failed to create installation token: HTTP " + response.statusCode() + " - " + response.body());
        }

        var responseBody = response.body();
        var tokenMarker = "\"token\":\"";
        var tokenStart = responseBody.indexOf(tokenMarker) + tokenMarker.length();
        var tokenEnd = responseBody.indexOf('"', tokenStart);
        if (tokenStart < tokenMarker.length() || tokenEnd < 0) {
            throw new RuntimeException("No token found in installation token response: " + responseBody);
        }
        return responseBody.substring(tokenStart, tokenEnd);
    }

    private String createJwt() throws Exception {
        var now = Instant.now();
        var header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        var payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"iat\":" + now.getEpochSecond()
                        + ",\"exp\":" + (now.getEpochSecond() + TOKEN_TTL_SECONDS)
                        + ",\"iss\":\"" + appId + "\"}")
                        .getBytes(StandardCharsets.UTF_8));

        var signingInput = header + "." + payload;
        var signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(privateKey);
        signer.update(signingInput.getBytes(StandardCharsets.UTF_8));
        var signature = Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign());

        return signingInput + "." + signature;
    }
}
