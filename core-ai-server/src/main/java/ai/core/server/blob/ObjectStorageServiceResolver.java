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
    @Inject
    SystemSettingsService settings;

    public String provider = "";
    public String minioEndpoint;
    public String minioAccessKey;
    public String minioSecretKey;
    public String minioRegion = "us-east-1";
    public String minioMultimodalBucket = "uploads";
    public String minioSandboxBucket = "sandbox-uploads";
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

    private String fingerprint() {
        return provider + "|" + settings.azureBlobAccountName() + "|" + settings.azureBlobAccountKey()
                + "|" + settings.azureBlobPublicBaseUrl() + "|" + minioEndpoint + "|" + minioAccessKey
                + "|" + minioSecretKey + "|" + minioPublicBaseUrl;
    }

    private ObjectStorageService build() {
        if (provider.isEmpty() || "azure".equals(provider)) {
            var sas = AzureBlobSasService.tryCreate(settings.azureBlobAccountName(), settings.azureBlobAccountKey());
            if (sas != null) {
                return new AzureObjectStorageService(sas, settings.azureBlobPublicBaseUrl());
            }
        }
        if (minioEndpoint != null && minioAccessKey != null
                && !minioEndpoint.isBlank() && !minioAccessKey.isBlank()) {
            return new MinioObjectStorageService(minioEndpoint, minioRegion, minioAccessKey, minioSecretKey, minioPublicBaseUrl);
        }
        return null;
    }

    public String multimodalContainer() {
        if (provider.isEmpty() || "azure".equals(provider)) {
            var container = settings.azureBlobMultimodalContainer();
            if (container != null && !container.isBlank()) return container;
        }
        return minioMultimodalBucket;
    }

    public String sandboxContainer() {
        if (provider.isEmpty() || "azure".equals(provider)) return "sandbox";
        return minioSandboxBucket;
    }
}
