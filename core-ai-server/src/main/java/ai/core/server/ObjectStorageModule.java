package ai.core.server;

import ai.core.api.server.FileWebService;
import ai.core.api.server.blob.BlobUploadCredentialView;
import ai.core.server.blob.BlobUploadCredentialController;
import ai.core.server.blob.ObjectStorageServiceResolver;
import ai.core.server.file.FileDownloadController;
import ai.core.server.file.FileService;
import ai.core.server.file.FileStorageMigrationJob;
import ai.core.server.file.FileUploadController;
import ai.core.server.file.SharedFileDownloadController;
import ai.core.server.web.FileWebServiceImpl;
import core.framework.http.HTTPMethod;
import core.framework.module.Module;

import java.time.Duration;

/**
 * @author stephen
 */
public class ObjectStorageModule extends Module {
    private String provider;
    private String minioEndpoint;
    private String minioAccessKey;
    private String minioSecretKey;
    private String minioRegion;
    private String minioMultimodalBucket;
    private String minioSandboxBucket;
    private String minioPublicBaseUrl;

    @Override
    protected void initialize() {
        readProperties();
        bindObjectStorageResolver();
        registerFile();
        bind(BlobUploadCredentialController.class);
        http().bean(BlobUploadCredentialView.class);
        http().route(HTTPMethod.GET, "/api/blob/upload-credential", bean(BlobUploadCredentialController.class)::getCredential);
    }

    private void bindObjectStorageResolver() {
        var resolver = bind(ObjectStorageServiceResolver.class);
        resolver.provider = provider;
        resolver.minioEndpoint = minioEndpoint;
        resolver.minioAccessKey = minioAccessKey;
        resolver.minioSecretKey = minioSecretKey;
        resolver.minioRegion = minioRegion;
        resolver.minioMultimodalBucket = minioMultimodalBucket;
        resolver.minioSandboxBucket = minioSandboxBucket;
        resolver.minioPublicBaseUrl = minioPublicBaseUrl;
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
    }

    private void registerFile() {
        bind(FileService.class);
        api().service(FileWebService.class, bind(FileWebServiceImpl.class));
        http().route(HTTPMethod.POST, "/api/files", bind(FileUploadController.class));
        http().route(HTTPMethod.GET, "/api/files/:id/content", bind(FileDownloadController.class));
        http().route(HTTPMethod.GET, "/api/public/artifacts/:token/content", bind(SharedFileDownloadController.class));
        schedule().fixedRate("file-storage-migration", bind(FileStorageMigrationJob.class), Duration.ofMinutes(5));
    }
}
