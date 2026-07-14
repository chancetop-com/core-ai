package ai.core.server;

import ai.core.api.server.FileWebService;
import ai.core.api.server.blob.BlobUploadCredentialView;
import ai.core.server.blob.AzureBlobSasService;
import ai.core.server.blob.AzureObjectStorageService;
import ai.core.server.blob.BlobUploadCredentialController;
import ai.core.server.blob.MinioObjectStorageService;
import ai.core.server.blob.ObjectStorageService;
import ai.core.server.file.FileDownloadController;
import ai.core.server.file.FileService;
import ai.core.server.file.FileUploadController;
import ai.core.server.file.SharedFileDownloadController;
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

    @Override
    protected void initialize() {
        registerFile();
        var blobController = configureObjectStorage();
        http().bean(BlobUploadCredentialView.class);
        http().route(HTTPMethod.GET, "/api/blob/upload-credential", blobController::getCredential);
    }

    private void registerFile() {
        bind(FileService.class);
        api().service(FileWebService.class, bind(FileWebServiceImpl.class));
        http().route(HTTPMethod.POST, "/api/files", bind(FileUploadController.class));
        http().route(HTTPMethod.GET, "/api/files/:id/content", bind(FileDownloadController.class));
        http().route(HTTPMethod.GET, "/api/public/artifacts/:token/content", bind(SharedFileDownloadController.class));
    }

    private BlobUploadCredentialController configureObjectStorage() {
        var blobController = bind(BlobUploadCredentialController.class);

        var provider = property("sys.storage.provider").orElse("");
        ObjectStorageService objectStorage = null;

        if (provider.isEmpty() || "azure".equals(provider)) {
            var azureAccountName = property("azure.blob.account.name").orElse(null);
            var azureAccountKey = property("azure.blob.account.key").orElse(null);
            var sasService = AzureBlobSasService.tryCreate(azureAccountName, azureAccountKey);
            if (sasService != null) {
                var azureMultimodalContainer = property("azure.blob.multimodal.container").orElse("uploads");
                var azureSandboxContainer = property("azure.blob.sandbox.container").orElse("sandbox-uploads");
                var azurePublicBaseUrl = property("azure.blob.public.base.url").orElse(null);
                blobController.multimodalContainer = azureMultimodalContainer;
                blobController.sandboxContainer = azureSandboxContainer;
                objectStorage = new AzureObjectStorageService(sasService, azurePublicBaseUrl);
                LOGGER.info("Object storage configured: provider=azure, multimodal={}, sandbox={}",
                        azureMultimodalContainer, azureSandboxContainer);
            }
        }
        if (objectStorage == null && (provider.isEmpty() || "minio".equals(provider))) {
            var minioEndpoint = property("storage.minio.endpoint").orElse(null);
            var minioAccessKey = property("storage.minio.access.key").orElse(null);
            if (minioEndpoint != null && !minioEndpoint.isBlank() && minioAccessKey != null && !minioAccessKey.isBlank()) {
                var minioSecretKey = property("storage.minio.secret.key").orElse(null);
                var minioRegion = property("storage.minio.region").orElse("us-east-1");
                var minioMultimodalBucket = property("storage.minio.multimodal.bucket").orElse("uploads");
                var minioSandboxBucket = property("storage.minio.sandbox.bucket").orElse("sandbox-uploads");
                var minioPublicBaseUrl = property("storage.minio.public.base.url").orElse(null);
                blobController.multimodalContainer = minioMultimodalBucket;
                blobController.sandboxContainer = minioSandboxBucket;
                objectStorage = new MinioObjectStorageService(minioEndpoint, minioRegion, minioAccessKey, minioSecretKey, minioPublicBaseUrl);
                LOGGER.info("Object storage configured: provider=minio, endpoint={}, multimodal={}, sandbox={}",
                        minioEndpoint, minioMultimodalBucket, minioSandboxBucket);
            }
        }

        if (objectStorage != null) {
            blobController.storageService = objectStorage;
        }

        return blobController;
    }
}
