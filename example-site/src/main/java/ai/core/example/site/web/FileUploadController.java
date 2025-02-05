package ai.core.example.site.web;

import ai.core.example.site.service.ImageService;
import ai.core.example.site.service.StorageService;
import core.framework.util.Encodings;
import core.framework.util.Files;
import core.framework.web.Controller;
import core.framework.web.Request;
import core.framework.web.Response;

import java.util.Map;

/**
 * @author stephen
 */
public class FileUploadController implements Controller {
    private final String modulePath;
    private final ImageService imageService;
    private final StorageService storageService;

    public FileUploadController(String modulePath, ImageService imageService, StorageService storageService) {
        this.modulePath = modulePath;
        this.imageService = imageService;
        this.storageService = storageService;
    }

    @Override
    public Response execute(Request request) {
        var response = new FileUploadResponse();
        var file = request.files().get("file");
        var cdn = Map.of(
                "marclore.png", "https://ftidevstoragev2.blob.core.windows.net/stephen/marclore.png",
                "tymko.jpg", "https://ftidevstoragev2.blob.core.windows.net/stephen/hdr/0dcfdfc5-f542-4c17-a3cd-fecf370b7a29.jpg",
                "mona.jpg", "https://ftidevstoragev2.blob.core.windows.net/stephen/hdr/22f7d93a-b38f-44a5-9384-ed872eebf2dc.jpg",
                "R.jpg", "https://ftidevstoragev2.blob.core.windows.net/stephen/R.jpg",
                "woman.jpg", "https://ftidevstoragev2.blob.core.windows.net/stephen/woman.jpg",
                "panda.jpg", "https://ftidevstoragev2.blob.core.windows.net/stephen/hdr/cfc75f5a-f0eb-416a-92a4-330b4b17c2f7.jpg",
                "mort.jpg", "https://ftidevstoragev2.blob.core.windows.net/stephen/hdr/e651831c-ef2b-4a67-8ed1-1324f1cd8df8.jpg",
                "miller.jpg", "https://ftidevstoragev2.blob.core.windows.net/stephen/hdr/98cdae22-008f-4062-81bf-734aff900e9d.jpg",
                "jun.jpg", "https://ftidevstoragev2.blob.core.windows.net/stephen/hdr/a139d1a7-47f4-4e45-a3a0-d1b3622f4c0d.jpg",
                "women2.webp", "https://ftidevstoragev2.blob.core.windows.net/stephen/women2.webp");

        response.url = cdn.get(file.fileName);
        if (response.url != null) {
            return Response.bean(response);
        }

        var s3Key = imageService.create(modulePath, file.fileName, Encodings.base64(Files.bytes(file.path)));

        response.url = storageService.url(s3Key);
        return Response.bean(response);
    }
}
