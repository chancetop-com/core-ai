package ai.core.server.gateway;

import ai.core.media.domain.ImageData;
import ai.core.media.domain.ImageGenerationResponse;
import ai.core.media.domain.VideoStatusResponse;
import ai.core.server.domain.FileRecord;
import ai.core.server.domain.GatewayProviderConfig;
import ai.core.server.domain.MediaJob;
import ai.core.server.file.FileService;
import ai.core.server.trace.service.MediaPricingService;
import com.mongodb.MongoClientSettings;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import core.framework.web.exception.BadRequestException;
import org.bson.BsonDocument;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author Stephen
 */
class MediaJobServiceTest {
    private MediaJobService service;
    private MongoCollection<MediaJob> collection;
    private MediaCostSettler costSettler;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = new MediaJobService();
        collection = (MongoCollection<MediaJob>) mock(MongoCollection.class);
        costSettler = mock(MediaCostSettler.class);
        service.mediaJobCollection = collection;
        service.costSettler = costSettler;
        service.fileService = mock(FileService.class);
    }

    private GatewayRoute route() {
        var provider = new GatewayProviderConfig();
        provider.id = "provider-1";
        return new GatewayRoute(provider, "upstream-model");
    }

    @Test
    void createVideoJobRecordsTypeAndRequestedSeconds() {
        var job = service.createVideoJob(new MediaJobOwner("user-1", "session-1", null), route(), "requested-model", "upstream-id", null, 8);

        assertEquals("video", job.mediaType);
        assertEquals(8, job.requestedSeconds);
        assertEquals("submitted", job.state);
        assertEquals("user-1", job.userId);
        assertEquals("session-1", job.sessionId);
        verify(collection).insert(job);
    }

    @Test
    void createImageJobRecordsCompletedImageJobWithCostFields() {
        var price = new MediaPricingService.MediaPrice(0.042, "model_catalog", "gpt-image-2", 200.0, "token");

        var job = service.createImageJob(new MediaJobOwner("user-1", "session-1", null), route(), "gpt-image-2", price, null);

        assertEquals("image", job.mediaType);
        assertEquals("completed", job.state);
        assertEquals(0.042, job.costUsd);
        assertEquals("model_catalog", job.costSource);
        assertEquals("gpt-image-2", job.pricingModelId);
        assertEquals(200.0, job.mediaUnits);
        assertEquals("token", job.mediaUnitType);
        assertNotNull(job.completedAt);
        assertNull(job.fileId);
        verify(collection).insert(job);
    }

    @Test
    void createImageJobStoresFirstImageAsArtifact() {
        var price = new MediaPricingService.MediaPrice(0.042, "model_catalog", "gpt-image-2", 200.0, "token");
        var record = new FileRecord();
        record.id = "file-1";
        record.fileName = "generated-image.png";
        record.contentType = "image/png";
        when(service.fileService.upload(any(), any(), any(), any())).thenReturn(record);
        var image = new ImageData(Base64.getEncoder().encodeToString("png-bytes".getBytes(StandardCharsets.UTF_8)), null, null);
        var response = new ImageGenerationResponse(List.of(image), null);

        var job = service.createImageJob(new MediaJobOwner("user-1", null, null), route(), "gpt-image-2", price, response);

        assertEquals("file-1", job.fileId);
        assertEquals("generated-image.png", job.fileName);
        assertEquals("image/png", job.contentType);
    }

    @Test
    void createImageJobSkipsArtifactWhenImageMissing() {
        var price = new MediaPricingService.MediaPrice(0.042, "model_catalog", "gpt-image-2", 200.0, "token");

        var job = service.createImageJob(new MediaJobOwner("user-1", null, null), route(), "gpt-image-2", price, new ImageGenerationResponse(List.of(), null));

        assertNull(job.fileId);
        verify(service.fileService, never()).upload(any(), any(), any(), any());
    }

    @Test
    void updateVideoStatusSettlesCostOnFirstCompletion() {
        var job = new MediaJob();
        job.id = "job-1";
        job.providerId = "provider-1";
        job.requestedModel = "veo";
        job.resolvedModel = "veo-3.1-generate-001";
        job.requestedSeconds = 8;
        var price = new MediaPricingService.MediaPrice(3.2, "model_catalog", "gemini/veo-3.1-generate-001", 8.0, "second");
        when(costSettler.settleVideo(any(), any())).thenReturn(price);

        service.updateVideoStatus(job, new VideoStatusResponse("upstream", "completed", 100, null, null, 12.5, null));

        var update = capturedUpdateText();
        assertTrue(update.contains("fieldName='completed_at'"));
        assertTrue(update.contains("fieldName='cost_usd', operator='$set', value=3.2"));
        assertTrue(update.contains("fieldName='cost_source', operator='$set', value=model_catalog"));
        assertTrue(update.contains("fieldName='pricing_model_id', operator='$set', value=gemini/veo-3.1-generate-001"));
        assertTrue(update.contains("fieldName='media_units', operator='$set', value=8.0"));
        assertTrue(update.contains("fieldName='media_unit_type', operator='$set', value=second"));
        assertTrue(update.contains("fieldName='credits_consumed', operator='$set', value=12.5"));
        assertNotNull(job.completedAt);
    }

    @Test
    void updateVideoStatusDoesNotSettleTwice() {
        var job = new MediaJob();
        job.id = "job-1";
        job.completedAt = ZonedDateTime.now();

        service.updateVideoStatus(job, new VideoStatusResponse("upstream", "completed", 100, null, null, null, null));

        verify(costSettler, never()).settleVideo(any(), any());
        assertFalse(capturedUpdateText().contains("cost_usd"));
    }

    @Test
    void updateVideoStatusSurvivesSettlementFailure() {
        var job = new MediaJob();
        job.id = "job-1";
        job.providerId = "provider-1";
        when(costSettler.settleVideo(any(), any())).thenThrow(new IllegalStateException("catalog exploded"));

        service.updateVideoStatus(job, new VideoStatusResponse("upstream", "completed", 100, null, null, null, null));

        var update = capturedUpdateText();
        assertTrue(update.contains("fieldName='state', operator='$set', value=completed"));
        assertTrue(update.contains("fieldName='completed_at'"));
        assertFalse(update.contains("cost_usd"));
        assertNotNull(job.completedAt);
    }

    @Test
    void updateVideoStatusKeepsCreditsEvenWithoutCompletion() {
        var job = new MediaJob();
        job.id = "job-1";

        service.updateVideoStatus(job, new VideoStatusResponse("upstream", "processing", 50, null, null, 3.0, null));

        var update = capturedUpdateText();
        assertTrue(update.contains("fieldName='state', operator='$set', value=processing"));
        assertTrue(update.contains("fieldName='credits_consumed', operator='$set', value=3.0"));
        assertFalse(update.contains("cost_usd"));
        assertNull(job.completedAt);
    }

    @Test
    void listFiltersVideoTypeIncludingLegacyNulls() {
        when(collection.count(any(Bson.class))).thenReturn(2L);
        when(collection.find(any(Query.class))).thenReturn(List.of());

        service.list(0, 20, "video", null, null);

        ArgumentCaptor<Bson> filter = ArgumentCaptor.forClass(Bson.class);
        verify(collection).count(filter.capture());
        var document = filter.getValue().toBsonDocument(BsonDocument.class, MongoClientSettings.getDefaultCodecRegistry());
        assertEquals("video", document.getDocument("media_type").getArray("$in").get(0).asString().getValue());
    }

    @Test
    void listFiltersImageTypeExactly() {
        when(collection.count(any(Bson.class))).thenReturn(0L);
        when(collection.find(any(Query.class))).thenReturn(List.of());

        service.list(0, 20, "image", null, null);

        ArgumentCaptor<Bson> filter = ArgumentCaptor.forClass(Bson.class);
        verify(collection).count(filter.capture());
        var document = filter.getValue().toBsonDocument(BsonDocument.class, MongoClientSettings.getDefaultCodecRegistry());
        assertEquals("image", document.getString("media_type").getValue());
    }

    @Test
    void listRejectsUnknownMediaTypeAndCostSource() {
        assertThrows(BadRequestException.class, () -> service.list(0, 20, "audio", null, null));
        assertThrows(BadRequestException.class, () -> service.list(0, 20, null, "magic", null));
    }

    @Test
    void fileRecordByHandleResolvesCompletedJobFile() {
        var job = new MediaJob();
        job.id = "job-1";
        job.state = "completed";
        job.fileId = "file-1";
        when(collection.get("job-1")).thenReturn(java.util.Optional.of(job));
        var record = new FileRecord();
        record.id = "file-1";
        when(service.fileService.get("file-1")).thenReturn(record);

        var resolved = service.fileRecordByHandle(GatewayMediaHandle.encodeImage("job-1"));

        assertTrue(resolved.isPresent());
        assertEquals("file-1", resolved.get().id);
    }

    @Test
    void fileRecordByHandleIsEmptyForNonHandlesUnknownOrPendingJobs() {
        assertTrue(service.fileRecordByHandle("file-1").isEmpty(), "plain file ids are not handles");
        assertTrue(service.fileRecordByHandle("gateway-media-v1.img.!!!").isEmpty(), "malformed handles never throw");

        when(collection.get("job-2")).thenReturn(java.util.Optional.empty());
        assertTrue(service.fileRecordByHandle(GatewayMediaHandle.encodeImage("job-2")).isEmpty());

        var pending = new MediaJob();
        pending.id = "job-3";
        pending.state = "submitted";
        when(collection.get("job-3")).thenReturn(java.util.Optional.of(pending));
        assertTrue(service.fileRecordByHandle(GatewayMediaHandle.encodeImage("job-3")).isEmpty());
    }

    private String capturedUpdateText() {
        ArgumentCaptor<Bson> captor = ArgumentCaptor.forClass(Bson.class);
        verify(collection).update(any(Bson.class), captor.capture());
        return captor.getValue().toString();
    }
}
