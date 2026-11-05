package ai.core.server.channel;

import ai.core.server.blob.ObjectStorageService;
import ai.core.server.blob.ObjectStorageServiceResolver;
import ai.core.utils.ImageFormats;
import core.framework.inject.Inject;
import core.framework.web.exception.BadRequestException;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Imports channel images into the same object storage used by chat attachments.
 *
 * @author xander
 */
public class ChannelImageAttachments {
    private static final int MAX_IMAGES = 10;
    private static final int MAX_BYTES = 6 * 1024 * 1024;
    private static final int MAX_ENCODED_LENGTH = (MAX_BYTES + 2) / 3 * 4;

    @Inject
    ObjectStorageServiceResolver objectStorageResolver;

    public List<Map<String, String>> store(List<String> imageUrls) {
        if (imageUrls == null || imageUrls.isEmpty()) return List.of();
        var images = decodeImages(imageUrls);
        var storage = objectStorageResolver.resolve();
        if (storage == null) throw new IllegalStateException("object storage is not configured for channel images");
        var container = objectStorageResolver.multimodalContainer();
        var uploaded = new ArrayList<String>(images.size());
        var attachments = new ArrayList<Map<String, String>>(images.size());
        try {
            for (var image : images) {
                var fileName = UUID.randomUUID() + "." + image.format();
                var blobName = "ai/uploads/" + fileName;
                uploaded.add(blobName);
                storage.uploadObject(container, blobName, image.bytes(), image.contentType());
                var url = downloadUrl(storage, container, blobName);
                attachments.add(Map.of("type", "IMAGE", "fileName", fileName, "contentType", image.contentType(),
                        "container", container, "blobName", blobName, "url", url));
            }
            return List.copyOf(attachments);
        } catch (RuntimeException | Error failure) {
            cleanup(storage, container, uploaded, failure);
            throw failure;
        }
    }

    private String downloadUrl(ObjectStorageService storage, String container, String blobName) {
        var publicUrl = storage.publicUrl(container, blobName);
        if (publicUrl != null && !publicUrl.isBlank()) return publicUrl;
        var credential = storage.generateDownloadCredential(container, blobName);
        if (credential == null || credential.downloadUrl() == null || credential.downloadUrl().isBlank()) {
            throw new IllegalStateException("object storage did not provide a channel image download URL");
        }
        return credential.downloadUrl();
    }

    private List<DecodedImage> decodeImages(List<String> imageUrls) {
        if (imageUrls.size() > MAX_IMAGES) throw new BadRequestException("channel messages support at most 10 images");
        var images = new ArrayList<DecodedImage>(imageUrls.size());
        var totalBytes = 0;
        for (var imageUrl : imageUrls) {
            var image = decodeImage(imageUrl);
            totalBytes += image.bytes().length;
            if (totalBytes > MAX_BYTES) throw new BadRequestException("channel images exceed the 6MB total size limit");
            images.add(image);
        }
        return images;
    }

    private DecodedImage decodeImage(String imageUrl) {
        if (imageUrl == null || !imageUrl.startsWith("data:")) {
            throw new BadRequestException("channel images must use inline base64 data URLs");
        }
        var comma = imageUrl.indexOf(',');
        if (comma < 5) throw new BadRequestException("invalid channel image data URL");
        var header = imageUrl.substring(5, comma).toLowerCase(Locale.ROOT);
        if (!header.endsWith(";base64")) throw new BadRequestException("channel image data URLs must use base64");
        var contentType = header.substring(0, header.length() - ";base64".length());
        var encodedLength = imageUrl.length() - comma - 1;
        if (encodedLength > MAX_ENCODED_LENGTH) throw new BadRequestException("channel images exceed the 6MB total size limit");
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(imageUrl.substring(comma + 1));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("invalid channel image base64", "BAD_REQUEST", e);
        }
        var format = ImageFormats.detect(bytes);
        if (!ImageFormats.isModelReadable(format)) {
            throw new BadRequestException("channel images must contain a " + ImageFormats.READABLE_FORMAT_LIST + " image");
        }
        if (!ImageFormats.mimeType(format).equals(contentType)) {
            throw new BadRequestException("channel image content does not match its declared media type");
        }
        return new DecodedImage(bytes, format, contentType);
    }

    private void cleanup(ObjectStorageService storage, String container, List<String> uploaded, Throwable failure) {
        for (var blobName : uploaded) {
            try {
                storage.deleteObject(container, blobName);
            } catch (RuntimeException cleanupFailure) {
                if (!cleanupFailure.equals(failure)) failure.addSuppressed(cleanupFailure);
            }
        }
    }

    private record DecodedImage(byte[] bytes, String format, String contentType) {
    }
}
