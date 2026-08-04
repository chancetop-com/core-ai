package ai.core.server.blob;

import ai.core.server.settings.SystemSettingsService;
import core.framework.inject.Inject;

/**
 * Resolves the active object storage service from current system settings.
 * <p>
 * Unlike {@link ObjectStorageConfiguration} (a startup-time holder), this resolver reads
 * settings on every call, so multi-replica deployments stay consistent without restart:
 * azure credentials come from Mongo system settings, minio config is injected from properties.
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

    public ObjectStorageService resolve() {
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
