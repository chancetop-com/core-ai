package ai.core.example.site;

import ai.core.example.site.service.ImageService;
import ai.core.example.site.service.StorageService;
import ai.core.example.site.web.FileUploadController;
import ai.core.example.site.web.FileUploadResponse;
import core.framework.http.HTTPMethod;
import core.framework.module.Module;

/**
 * @author stephen
 */
public class FileModule extends Module {
    @Override
    protected void initialize() {
        http().bean(FileUploadResponse.class);

        var storageService = new StorageService(
                requiredProperty("azure.storage.account"),
                requiredProperty("azure.storage.account.key"),
                requiredProperty("azure.storage.container"));
        bind(storageService);
        var imageService = bind(ImageService.class);

        var fileUploadController = new FileUploadController("hdr", imageService, storageService);
        http().route(HTTPMethod.POST, "/ajax/example/upload-file", fileUploadController);
    }
}
