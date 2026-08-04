package ai.core.server;

import ai.core.api.server.FileWebService;
import ai.core.api.server.blob.BlobUploadCredentialView;
import ai.core.server.blob.BlobUploadCredentialController;
import ai.core.server.blob.ObjectStorageConfiguration;
import ai.core.server.blob.ObjectStorageService;
import ai.core.server.blob.ObjectStorageServiceResolver;
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
    private boolean objectStorageBound;

    @Override
    protected void initialize() {
        readProperties();
        registerFile();
        bindObjectStorageResolver();
        bind(ObjectStorageConfiguration.class);
        bind(BlobUploadCredentialController.class);
        http().bean(BlobUploadCredentialView.class);
        http().route(HTTPMethod.GET, "/api/blob/upload-credential", bean(BlobUploadCredentialController.class)::getCredential);
        // azure config now comes from Mongo system settings, which is only available after startup hooks initialize
        onStartup(() -> {
            configureObjectStorage();
            bean(SystemSettingsService.class).onSettingsChanged(this::configureObjectStorage);
        });
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
    }

    private void configureObjectStorage() {
        var resolver = bean(ObjectStorageServiceResolver.class);
        var objectStorage = resolver.resolve();
        var multimodalContainer = resolver.multimodalContainer();

        if (objectStorage != null && !objectStorageBound) {
            bind(ObjectStorageService.class, objectStorage);
            objectStorageBound = true;
        }
        bean(ObjectStorageConfiguration.class).service = objectStorage;
        bean(ObjectStorageConfiguration.class).multimodalContainer = multimodalContainer;
        if (objectStorage != null) {
            var sandboxContainer = resolver.sandboxContainer();
            LOGGER.info("Object storage configured: provider={}, multimodal={}, sandbox={}",
                    provider, multimodalContainer, sandboxContainer);
        } else {
            LOGGER.warn("Object storage not configured: provider={}", provider);
        }
    }
}
