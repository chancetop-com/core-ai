package ai.core.server;

import ai.core.api.server.FileWebService;
import ai.core.api.server.blob.BlobUploadCredentialView;
import ai.core.server.blob.AzureBlobSasService;
import ai.core.server.blob.AzureObjectStorageService;
import ai.core.server.blob.BlobUploadCredentialController;
import ai.core.server.blob.MinioObjectStorageService;
import ai.core.server.blob.ObjectStorageConfiguration;
import ai.core.server.blob.ObjectStorageService;
import ai.core.server.file.FileDownloadController;
import ai.core.server.file.FileService;
import ai.core.server.file.FileUploadController;
import ai.core.server.file.SharedFileDownloadController;
import ai.core.server.settings.SystemSettingsService;
import ai.core.server.web.FileWebServiceImpl;
import core.framework.http.HTTPMethod;
import core.framework.module.Module;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author stephen
 */
public class ObjectStorageModule extends Module {
    private static final Logger LOGGER = LoggerFactory.getLogger(ObjectStorageModule.class);

    private String provider;
    private String minioEndpoint;
    private String minioAccessKey;
    private String minioSecretKey;
    private String minioRegion;
    private String minioMultimodalBucket;
    private String minioSandboxBucket;
    private String minioPublicBaseUrl;
    private String azureSandboxContainer;
    private boolean objectStorageBound;

    @Override
    protected void initialize() {
        readProperties();
        registerFile();
        bind(ObjectStorageConfiguration.class);
        var blobController = bind(BlobUploadCredentialController.class);
        http().bean(BlobUploadCredentialView.class);
        http().route(HTTPMethod.GET, "/api/blob/upload-credential", blobController::getCredential);
        // azure config now comes from Mongo system settings, which is only available after startup hooks initialize
        onStartup(() -> {
            configureObjectStorage(blobController);
            bean(SystemSettingsService.class).onSettingsChanged(() -> configureObjectStorage(blobController));
        });
    }

    private void readProperties() {
        provider = property("sys.storage.provider").orElse("");
        minioEndpoint = property("storage.minio.endpoint").orElse(null);
        minioAccessKey = property("storage.minio.access.key").orElse(null);
        minioSecretKey = property("storage.minio.secret.key").orElse(null);
        minioRegion = property("storage.minio.region").orElse("us-east-1");
        minioMultimodalBucket = property("storage.minio.multimodal.bucket").orElse("uploads");
        minioSandboxBucket = property("storage.minio.sandbox.bucket").orElse("sandbox-uploads");
        minioPublicBaseUrl = property("storage.minio.public.base.url").orElse(null);
        azureSandboxContainer = property("azure.blob.sandbox.container").orElse("sandbox");
    }

    private void registerFile() {
        bind(FileService.class);
        api().service(FileWebService.class, bind(FileWebServiceImpl.class));
        http().route(HTTPMethod.POST, "/api/files", bind(FileUploadController.class));
        http().route(HTTPMethod.GET, "/api/files/:id/content", bind(FileDownloadController.class));
        http().route(HTTPMethod.GET, "/api/public/artifacts/:token/content", bind(SharedFileDownloadController.class));
    }

    private void configureObjectStorage(BlobUploadCredentialController blobController) {
        ObjectStorageService objectStorage = null;

        if (provider.isEmpty() || "azure".equals(provider)) {
            var settings = bean(SystemSettingsService.class);
            var azureAccountName = readSetting(settings::azureBlobAccountName);
            var azureAccountKey = readSetting(settings::azureBlobAccountKey);
            var sasService = AzureBlobSasService.tryCreate(azureAccountName, azureAccountKey);
            if (sasService != null) {
                var azureMultimodalContainer = firstNonBlank(readSetting(settings::azureBlobMultimodalContainer), "uploads");
                var azurePublicBaseUrl = readSetting(settings::azureBlobPublicBaseUrl);
                blobController.multimodalContainer = azureMultimodalContainer;
                blobController.sandboxContainer = azureSandboxContainer;
                objectStorage = new AzureObjectStorageService(sasService, azurePublicBaseUrl);
                LOGGER.info("Object storage configured: provider=azure, multimodal={}, sandbox={}",
                        azureMultimodalContainer, azureSandboxContainer);
            }
        }
        if (objectStorage == null && minioEndpoint != null && minioAccessKey != null && (provider.isEmpty() || "minio".equals(provider)) && !minioEndpoint.isBlank() && !minioAccessKey.isBlank()) {
            blobController.multimodalContainer = minioMultimodalBucket;
            blobController.sandboxContainer = minioSandboxBucket;
            objectStorage = new MinioObjectStorageService(minioEndpoint, minioRegion, minioAccessKey, minioSecretKey, minioPublicBaseUrl);
            LOGGER.info("Object storage configured: provider=minio, endpoint={}, multimodal={}, sandbox={}", minioEndpoint, minioMultimodalBucket, minioSandboxBucket);
        }

        if (objectStorage != null) {
            blobController.storageService = objectStorage;
            if (!objectStorageBound) {
                bind(ObjectStorageService.class, objectStorage);
                objectStorageBound = true;
            }
        } else {
            blobController.storageService = null;
        }
        bean(ObjectStorageConfiguration.class).service = objectStorage;
        bean(ObjectStorageConfiguration.class).multimodalContainer = blobController.multimodalContainer;
    }

    private String readSetting(java.util.function.Supplier<String> getter) {
        try {
            return getter.get();
        } catch (Exception e) {
            LOGGER.warn("failed to read system settings, object storage may be disabled", e);
            return null;
        }
    }

    private String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
