package ai.core.server.gateway;

import ai.core.api.server.media.ImageCompareRunRequest;
import ai.core.media.domain.ImageGenerationRequest;
import ai.core.media.domain.ImageGenerationResponse;
import ai.core.server.domain.GatewayModelConfig;
import ai.core.server.domain.MediaJob;
import ai.core.tool.tools.MediaModelHint;
import core.framework.web.exception.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author stephen
 */
class ImageModelCompareServiceTest {
    private ImageModelCompareService service;
    private GatewayRoutingEngine routingEngine;
    private GatewayMediaProvider gatewayMediaProvider;
    private MediaJobService mediaJobService;

    @BeforeEach
    void setUp() {
        service = new ImageModelCompareService();
        routingEngine = mock(GatewayRoutingEngine.class);
        gatewayMediaProvider = mock(GatewayMediaProvider.class);
        mediaJobService = mock(MediaJobService.class);
        service.routingEngine = routingEngine;
        service.gatewayMediaProvider = gatewayMediaProvider;
        service.mediaJobService = mediaJobService;
    }

    @Test
    void modelsCarryProviderPriceAndParameterHint() {
        when(routingEngine.mediaModelHints(GatewayEndpointType.IMAGE_GENERATION))
            .thenReturn(List.of(new MediaModelHint("gpt-image-2.5", "gpt-image-2.5-2026", "azure-uat")));
        var config = new GatewayModelConfig();
        config.imagePricePerImage = 0.04;
        when(routingEngine.modelConfig("gpt-image-2.5")).thenReturn(config);

        var response = service.models();

        var model = response.models.getFirst();
        assertEquals("gpt-image-2.5", model.modelId);
        assertEquals("gpt-image-2.5-2026", model.upstreamModel);
        assertEquals("azure-uat", model.providerName);
        assertEquals(0.04, model.imagePricePerImage);
        assertNotNull(model.hint);
    }

    @Test
    void modelsTolerateMissingPriceConfig() {
        when(routingEngine.mediaModelHints(GatewayEndpointType.IMAGE_GENERATION))
            .thenReturn(List.of(new MediaModelHint("seedream-4", "seedream/4", "kie")));
        when(routingEngine.modelConfig("seedream-4")).thenReturn(null);

        var response = service.models();

        assertNull(response.models.getFirst().imagePricePerImage);
    }

    @Test
    void runGeneratesOneImageAndReturnsItsJob() {
        var job = job("job-1");
        when(gatewayMediaProvider.generateImage(any(), any()))
            .thenReturn(new ImageGenerationResponse(List.of(), null).with(GatewayMediaHandle.encodeImage("job-1"), List.of()));
        when(mediaJobService.resolveReference(eq(GatewayMediaHandle.encodeImage("job-1")), any())).thenReturn(job);

        var response = service.run(request("a cat on a skateboard", "gpt-image-2.5"), "admin");

        assertEquals("job-1", response.jobId);
        assertEquals("file-1", response.fileId);
        assertEquals("gpt-image-2.5", response.model);
        assertEquals("gpt-image-2.5-2026", response.resolvedModel);
        assertEquals(0.04, response.costUsd);
        assertEquals("gateway_model", response.costSource);
        assertNotNull(response.durationMs);
    }

    @Test
    void runBillsTheCallerAndPassesSizeAndQuality() {
        when(gatewayMediaProvider.generateImage(any(), any()))
            .thenReturn(new ImageGenerationResponse(List.of(), null).with(GatewayMediaHandle.encodeImage("job-1"), List.of()));
        when(mediaJobService.resolveReference(any(), any())).thenReturn(job("job-1"));
        var request = request("a cat", "gpt-image-2.5");
        request.size = " 1024x1024 ";
        request.quality = "high";

        service.run(request, "admin");

        var requestCaptor = ArgumentCaptor.forClass(ImageGenerationRequest.class);
        var ownerCaptor = ArgumentCaptor.forClass(MediaJobOwner.class);
        verify(gatewayMediaProvider).generateImage(requestCaptor.capture(), ownerCaptor.capture());
        assertEquals("1024x1024", requestCaptor.getValue().size());
        assertEquals("high", requestCaptor.getValue().quality());
        assertEquals(1, requestCaptor.getValue().n());
        assertEquals("admin", ownerCaptor.getValue().userId());
    }

    @Test
    void runRejectsBlankPromptOrModel() {
        assertThrows(BadRequestException.class, () -> service.run(request("  ", "gpt-image-2.5"), "admin"));
        assertThrows(BadRequestException.class, () -> service.run(request("a cat", " "), "admin"));
    }

    @Test
    void runFailsWhenTheGenerationWasNotRecorded() {
        when(gatewayMediaProvider.generateImage(any(), any()))
            .thenReturn(new ImageGenerationResponse(List.of(), null));

        assertThrows(IllegalStateException.class, () -> service.run(request("a cat", "gpt-image-2.5"), "admin"));
    }

    private ImageCompareRunRequest request(String prompt, String model) {
        var request = new ImageCompareRunRequest();
        request.prompt = prompt;
        request.model = model;
        return request;
    }

    private MediaJob job(String id) {
        var job = new MediaJob();
        job.id = id;
        job.fileId = "file-1";
        job.resolvedModel = "gpt-image-2.5-2026";
        job.costUsd = 0.04;
        job.costSource = "gateway_model";
        job.state = "completed";
        return job;
    }
}
