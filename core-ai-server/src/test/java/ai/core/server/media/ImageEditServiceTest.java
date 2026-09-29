package ai.core.server.media;

import ai.core.api.server.media.ImageEditRequest;
import ai.core.media.MediaProvider;
import ai.core.media.domain.ImageGenerationRequest;
import ai.core.media.domain.ImageGenerationResponse;
import ai.core.media.domain.MediaReference;
import ai.core.server.blob.ObjectStorageService;
import ai.core.server.blob.ObjectStorageServiceResolver;
import ai.core.server.domain.FileRecord;
import ai.core.server.domain.GatewayModelConfig;
import ai.core.server.domain.GatewayProviderConfig;
import ai.core.server.domain.MediaJob;
import ai.core.server.file.FileService;
import ai.core.server.gateway.GatewayEndpointType;
import ai.core.server.gateway.GatewayMediaHandle;
import ai.core.server.gateway.GatewayRoute;
import ai.core.server.gateway.GatewayRoutingEngine;
import ai.core.server.gateway.MediaJobService;
import ai.core.server.settings.SystemSettingsService;
import ai.core.tool.tools.MediaModelHint;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.ForbiddenException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author stephen
 */
class ImageEditServiceTest {
    private static BufferedImage maskImage() {
        var image = white(200, 200);
        var graphics = image.createGraphics();
        try {
            graphics.setComposite(AlphaComposite.Clear);
            graphics.fillRect(50, 50, 100, 100);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private static BufferedImage white(int width, int height) {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private static byte[] png(BufferedImage image) {
        var output = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", output);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return output.toByteArray();
    }

    private static String base64(byte[] bytes) {
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes);
    }

    private ImageEditService service;
    private FileService fileService;
    private ObjectStorageServiceResolver storageResolver;
    private ObjectStorageService storageService;
    private MediaProvider mediaProvider;
    private MediaJobService mediaJobService;
    private GatewayRoutingEngine routingEngine;
    private SystemSettingsService systemSettingsService;

    @BeforeEach
    void setUp() {
        service = new ImageEditService();
        fileService = mock(FileService.class);
        storageResolver = mock(ObjectStorageServiceResolver.class);
        storageService = mock(ObjectStorageService.class);
        mediaProvider = mock(MediaProvider.class);
        mediaJobService = mock(MediaJobService.class);
        routingEngine = mock(GatewayRoutingEngine.class);
        systemSettingsService = mock(SystemSettingsService.class);
        service.fileService = fileService;
        service.storageResolver = storageResolver;
        service.mediaProvider = mediaProvider;
        service.mediaJobService = mediaJobService;
        service.routingEngine = routingEngine;
        service.systemSettingsService = systemSettingsService;
        when(routingEngine.route("gpt-image-2", GatewayEndpointType.IMAGE_EDIT))
            .thenReturn(route(provider("azure", "OPENAI_IMAGES"), "gpt-image-2"));
        when(routingEngine.route("seedream-5-pro-image-to-image", GatewayEndpointType.IMAGE_EDIT))
            .thenReturn(route(provider("kie", "KIE"), "seedream/5-pro-image-to-image"));
    }

    @Test
    void modelsCarryMaskCapabilityAndTheAnnotationFallback() {
        when(routingEngine.mediaModelHints(GatewayEndpointType.IMAGE_EDIT))
            .thenReturn(List.of(new MediaModelHint("gpt-image-2", "gpt-image-2", "azure"),
                new MediaModelHint("seedream-5-pro-image-to-image", "seedream/5-pro-image-to-image", "kie")));
        var gptImageConfig = new GatewayModelConfig();
        gptImageConfig.displayName = "GPT Image 2";
        when(routingEngine.modelConfig("gpt-image-2")).thenReturn(gptImageConfig);
        // the configured default is the model that cannot take a mask; the canvas must not preselect it
        when(systemSettingsService.imageGenerationModel()).thenReturn("seedream-5-pro-image-to-image");

        var response = service.models();

        assertEquals(2, response.models.size());
        assertTrue(response.models.get(0).maskSupported);
        assertNull(response.models.get(0).maskFallback);
        assertEquals("GPT Image 2", response.models.get(0).displayName);
        assertFalse(response.models.get(1).maskSupported);
        assertEquals("annotation", response.models.get(1).maskFallback);
        assertEquals("gpt-image-2", response.defaultModelId);
    }

    @Test
    void modelsDefaultToTheOnlyModelThatCanReceiveAReference() {
        when(routingEngine.mediaModelHints(GatewayEndpointType.IMAGE_EDIT))
            .thenReturn(List.of(new MediaModelHint("seedream-5-pro-image-to-image", "seedream/5-pro-image-to-image", "kie")));

        var response = service.models();

        assertEquals("seedream-5-pro-image-to-image", response.defaultModelId);
    }

    @Test
    void editSendsTheMaskToAMaskCapableModel() {
        ownsSource(1024, 1024);
        when(mediaProvider.generateImage(any())).thenReturn(new ImageGenerationResponse(List.of(), null, null, null, List.of("note")));

        var response = service.edit("user-1", request("gpt-image-2", null));

        var request = captured();
        assertNotNull(request.mask());
        assertEquals(1, request.inputImages().size());
        assertEquals("1024x1024", request.size());
        assertTrue(request.prompt().startsWith("make the sky green"));
        assertTrue(request.prompt().endsWith("only repaint the transparent area of the mask and keep everything else unchanged."));
        assertEquals("mask", response.maskMode);
        assertEquals(List.of("note"), response.notes);
    }

    @Test
    void editPaintsTheRegionForAModelWithoutAMaskField() {
        ownsSource(200, 200);
        when(mediaProvider.generateImage(any())).thenReturn(new ImageGenerationResponse(List.of(), null));

        var response = service.edit("user-1", request("seedream-5-pro-image-to-image", "annotation"));

        var request = captured();
        assertNull(request.mask());
        assertEquals(1, request.inputImages().size());
        MediaReference reference = request.inputImages().getFirst();
        var marked = reference.b64Json().substring(reference.b64Json().indexOf(',') + 1);
        assertNotEquals(Base64.getEncoder().encodeToString(png(white(200, 200))), marked,
            "the reference carries the marked copy, not the original");
        assertTrue(request.prompt().endsWith("do not render the orange marking in the result."));
        assertEquals("annotation", response.maskMode);
    }

    @Test
    void editRefusesAMaskIncapableModelUnlessTheCallerOptedIntoTheApproximation() {
        ownsSource(200, 200);

        var error = assertThrows(BadRequestException.class, () -> service.edit("user-1", request("seedream-5-pro-image-to-image", null)));

        assertTrue(error.getMessage().contains("cannot apply a region mask"));
    }

    @Test
    void editRejectsBlankPromptsAndForeignFiles() {
        ownsSource(200, 200);
        var blankPrompt = request("gpt-image-2", null);
        blankPrompt.prompt = "   ";
        assertThrows(BadRequestException.class, () -> service.edit("user-1", blankPrompt));

        when(fileService.getOwned("file-1", "user-1")).thenThrow(new ForbiddenException("file does not belong to current user"));
        assertThrows(ForbiddenException.class, () -> service.edit("user-1", request("gpt-image-2", null)));
    }

    @Test
    void editMapsUpstreamFailuresToAReadableError() {
        ownsSource(200, 200);
        when(mediaProvider.generateImage(any()))
            .thenThrow(new RuntimeException("image generation failed: HTTP 429: {\"error\":{\"code\":\"rate_limit_exceeded\"}}"));

        var error = assertThrows(BadRequestException.class, () -> service.edit("user-1", request("gpt-image-2", null)));

        assertTrue(error.getMessage().contains("busy"));
        assertEquals("IMAGE_EDIT_FAILED", error.errorCode());
    }

    @Test
    void editResolvesAChatImageByItsShareToken() {
        ownsSource(200, 200);
        var record = new FileRecord();
        record.id = "file-1";
        record.userId = "user-1";
        record.contentType = "image/png";
        when(fileService.getShared("share-1")).thenReturn(record);
        when(mediaProvider.generateImage(any())).thenReturn(new ImageGenerationResponse(List.of(), null));
        var request = request("gpt-image-2", null);
        request.sourceFileId = null;
        request.sourceShareToken = "share-1";

        var response = service.edit("user-1", request);

        verify(fileService).getOwned("file-1", "user-1");
        assertEquals("mask", response.maskMode);
    }

    @Test
    void editReadsAnUploadedAttachmentStraightFromObjectStorage() {
        when(storageResolver.locate("https://cdn.example.net/ai/uploads/a.png"))
            .thenReturn(new ObjectStorageServiceResolver.BlobLocation("static", "ai/uploads/a.png"));
        when(storageResolver.resolve()).thenReturn(storageService);
        when(storageService.downloadObject("static", "ai/uploads/a.png")).thenReturn(png(white(200, 200)));
        when(mediaProvider.generateImage(any())).thenReturn(new ImageGenerationResponse(List.of(), null));
        var request = request("gpt-image-2", null);
        request.sourceFileId = null;
        request.sourceUrl = "https://cdn.example.net/ai/uploads/a.png";

        var response = service.edit("user-1", request);

        assertEquals("mask", response.maskMode);
        assertEquals(1, captured().inputImages().size());
    }

    @Test
    void editRefusesAUrlThatIsNotPlatformStorage() {
        when(storageResolver.locate("https://example.com/cat.png")).thenReturn(null);
        var request = request("gpt-image-2", null);
        request.sourceFileId = null;
        request.sourceUrl = "https://example.com/cat.png";

        var error = assertThrows(BadRequestException.class, () -> service.edit("user-1", request));

        assertTrue(error.getMessage().contains("only images hosted by this platform"));
    }

    @Test
    void editRequiresExactlyOneSource() {
        ownsSource(200, 200);
        var neither = request("gpt-image-2", null);
        neither.sourceFileId = null;
        assertThrows(BadRequestException.class, () -> service.edit("user-1", neither));

        var both = request("gpt-image-2", null);
        both.sourceShareToken = "share-1";
        var error = assertThrows(BadRequestException.class, () -> service.edit("user-1", both));
        assertTrue(error.getMessage().contains("provide only one of"));
    }

    @Test
    void editReturnsTheRecordedJobOfTheResult() {
        ownsSource(200, 200);
        when(mediaProvider.generateImage(any()))
            .thenReturn(new ImageGenerationResponse(List.of(), null).with(GatewayMediaHandle.encodeImage("job-1"), List.of()));
        when(mediaJobService.get("job-1")).thenReturn(job());

        var response = service.edit("user-1", request("gpt-image-2", null));

        assertEquals("job-1", response.mediaJobId);
        assertEquals("file-9", response.fileId);
        assertEquals("/api/files/file-9/content", response.url);
        assertEquals("gpt-image-2-2026", response.model);
        assertEquals(0.04, response.costUsd);
        assertEquals("gateway_model", response.costSource);
        assertNotNull(response.elapsedMs);
    }

    private void ownsSource(int width, int height) {
        var record = new FileRecord();
        record.id = "file-1";
        record.userId = "user-1";
        record.contentType = "image/png";
        when(fileService.getOwned("file-1", "user-1")).thenReturn(record);
        when(fileService.getBytes(record)).thenReturn(png(white(width, height)));
    }

    private ImageEditRequest request(String model, String fallback) {
        var request = new ImageEditRequest();
        request.sourceFileId = "file-1";
        request.prompt = "make the sky green";
        request.mask = base64(png(maskImage()));
        request.model = model;
        request.maskFallback = fallback;
        return request;
    }

    private ImageGenerationRequest captured() {
        var captor = ArgumentCaptor.forClass(ImageGenerationRequest.class);
        verify(mediaProvider).generateImage(captor.capture());
        return captor.getValue();
    }

    private GatewayRoute route(GatewayProviderConfig provider, String upstreamModel) {
        var config = new GatewayModelConfig();
        config.modelId = upstreamModel;
        config.upstreamModel = upstreamModel;
        return new GatewayRoute(provider, upstreamModel, config);
    }

    private GatewayProviderConfig provider(String name, String mediaProtocol) {
        var provider = new GatewayProviderConfig();
        provider.id = name;
        provider.name = name;
        provider.mediaProtocol = mediaProtocol;
        return provider;
    }

    private MediaJob job() {
        var job = new MediaJob();
        job.id = "job-1";
        job.fileId = "file-9";
        job.resolvedModel = "gpt-image-2-2026";
        job.costUsd = 0.04;
        job.costSource = "gateway_model";
        job.state = "completed";
        return job;
    }
}
