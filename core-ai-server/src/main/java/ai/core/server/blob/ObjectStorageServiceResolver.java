package ai.core.server.blob;

import ai.core.server.settings.SystemSettingsService;
import core.framework.inject.Inject;

import java.util.Objects;

/**
 * Resolves the active object storage service from current system settings.
 * <p>
 * Reads settings on every call and caches the built service by configuration fingerprint,
 * so multi-replica deployments stay consistent without restart: azure credentials come
 * from Mongo system settings, minio config is injected from properties.
 *
 * @author stephen
 */
public class ObjectStorageServiceResolver {
    public static final String DEFAULT_PUBLIC_ARTIFACT_CONTAINER = "public-artifacts";

    @Inject
    SystemSettingsService settings;

    public String provider = "";
    public String minioEndpoint;
    public String minioAccessKey;
    public String minioSecretKey;
    public String minioRegion = "us-east-1";
    public String minioMultimodalBucket = "uploads";
    public String minioSandboxBucket = "sandbox-uploads";
    public String minioPublicArtifactBucket;
    public String minioPublicBaseUrl;

    private volatile String cachedFingerprint;
    private volatile ObjectStorageService cached;

    public ObjectStorageService resolve() {
        var fingerprint = fingerprint();
        if (!Objects.equals(fingerprint, cachedFingerprint)) {
            cached = build();
            cachedFingerprint = fingerprint;
        }
        return cached;
    }

    private String effectiveProvider() {
        var configured = settings.storageProvider();
        return configured != null && !configured.isBlank() ? configured : provider;
    }

    private String fingerprint() {
        return effectiveProvider() + "|" + settings.azureBlobAccountName() + "|" + settings.azureBlobAccountKey()
                + "|" + settings.azureBlobPublicBaseUrl() + "|" + minioEndpoint + "|" + minioAccessKey
                + "|" + minioSecretKey + "|" + minioPublicBaseUrl;
    }

    private ObjectStorageService build() {
        var activeProvider = effectiveProvider();
        if (activeProvider.isEmpty() || "azure".equals(activeProvider)) {
            var sas = AzureBlobSasService.tryCreate(settings.azureBlobAccountName(), settings.azureBlobAccountKey());
            if (sas != null) {
                return new AzureObjectStorageService(sas, ensureHttps(settings.azureBlobPublicBaseUrl()), ensureHttps(settings.azureBlobCdnBaseUrl()));
            }
        }
        if (minioEndpoint != null && minioAccessKey != null
                && !minioEndpoint.isBlank() && !minioAccessKey.isBlank()) {
            return new MinioObjectStorageService(minioEndpoint, minioRegion, minioAccessKey, minioSecretKey, ensureHttps(minioPublicBaseUrl));
        }
        return null;
    }

    public String multimodalContainer() {
        var activeProvider = effectiveProvider();
        if (activeProvider.isEmpty() || "azure".equals(activeProvider)) {
            var container = settings.azureBlobMultimodalContainer();
            if (container != null && !container.isBlank()) return container;
            return "static";
        }
        return minioMultimodalBucket;
    }

    public String sandboxContainer() {
        if (provider.isEmpty() || "azure".equals(provider)) return "sandbox";
        return minioSandboxBucket;
    }

    /** Private container for artifact files; downloads go through pre-signed URLs. */
    public String artifactContainer() {
        var activeProvider = effectiveProvider();
        if (activeProvider.isEmpty() || "azure".equals(activeProvider)) {
            var container = settings.azureBlobArtifactContainer();
            if (container != null && !container.isBlank()) return container;
        }
        return "artifacts";
    }

    /**
     * Public-read container artifacts are published to when the agent asks for external access.
     * Falls back to the default container name, so the destination stays stable until an admin
     * moves it; uploads that land here are served straight from object storage, no share link.
     */
    public String publicArtifactContainer() {
        var activeProvider = effectiveProvider();
        if (activeProvider.isEmpty() || "azure".equals(activeProvider)) {
            var container = settings.azureBlobPublicArtifactContainer();
            return container == null || container.isBlank() ? DEFAULT_PUBLIC_ARTIFACT_CONTAINER : container;
        }
        return minioPublicArtifactBucket == null || minioPublicArtifactBucket.isBlank()
                ? DEFAULT_PUBLIC_ARTIFACT_CONTAINER : minioPublicArtifactBucket;
    }

    /** Public base URL for frontend assets; null when not configured keeps serving from the local image. */
    public String webAssetsPublicBaseUrl() {
        var activeProvider = effectiveProvider();
        if (activeProvider.isEmpty() || "azure".equals(activeProvider)) {
            return ensureHttps(settings.azureBlobPublicBaseUrl());
        }
        return ensureHttps(minioPublicBaseUrl);
    }

    /**
     * Base URL for the /assets/* redirect. Prefers the CDN base (container-scoped, e.g. Front Door)
     * when configured; otherwise falls back to the blob domain + container path.
     */
    public String webAssetsRedirectBase() {
        var cdnBase = settings.azureBlobCdnBaseUrl();
        if (cdnBase != null && !cdnBase.isBlank()) {
            return stripTrailingSlash(ensureHttps(cdnBase)) + "/web-assets";
        }
        return webAssetsPublicBaseUrl() + "/" + multimodalContainer() + "/web-assets";
    }

    /**
     * Where a URL of ours lives in object storage, or null when it is not our storage at all.
     * A browser upload (chat attachment) is handed out either as a container-scoped CDN/Front Door
     * URL (the path is the blob name inside the multimodal container) or as a plain blob-domain URL
     * ({@code https://{account}.blob.core.windows.net/{container}/{blob}}).
     */
    public BlobLocation locate(String url) {
        if (url == null || url.isBlank()) return null;
        var normalized = stripTrailingSlash(url.trim());
        var cdnBase = settings.azureBlobCdnBaseUrl();
        if (cdnBase != null && !cdnBase.isBlank()) {
            var base = stripTrailingSlash(ensureHttps(cdnBase));
            if (normalized.startsWith(base + "/")) {
                return new BlobLocation(multimodalContainer(), normalized.substring(base.length() + 1));
            }
        }
        var marker = "blob.core.windows.net/";
        var index = normalized.indexOf(marker);
        if (index < 0) return null;
        var path = normalized.substring(index + marker.length());
        var slash = path.indexOf('/');
        if (slash <= 0 || slash == path.length() - 1) return null;
        return new BlobLocation(path.substring(0, slash), path.substring(slash + 1));
    }

    private String ensureHttps(String value) {
        if (value == null || value.isBlank()) return value;
        if (value.startsWith("http://") || value.startsWith("https://")) return value;
        return "https://" + value;
    }

    private String stripTrailingSlash(String value) {
        var result = value;
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }

    public record BlobLocation(String container, String blobName) {
    }
}
