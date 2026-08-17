package ai.core.server.seoops;

import ai.core.api.server.seoops.SeoOpsApiModels.PageRequest;
import ai.core.server.seoops.domain.SeoTask;
import ai.core.server.seoops.domain.SeoTaskStatus;
import com.mongodb.MongoClientSettings;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import core.framework.web.exception.BadRequestException;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author xander
 */
class SeoOpsQueryServiceTest {
    @Test
    void emptyVisibilityReturnsWithoutScanningTasks() {
        var service = service();
        when(service.merchantService.visibleMerchantIds("user-1")).thenReturn(List.of());

        var page = service.inbox("user-1", new PageRequest());

        assertEquals(0, page.total());
        verify(service.taskCollection, never()).find(any(core.framework.mongo.Query.class));
    }

    @Test
    void inboxClampsLimitAndFiltersVisibleMerchants() {
        var service = service();
        when(service.merchantService.visibleMerchantIds("user-1")).thenReturn(List.of("merchant-1"));
        when(service.taskCollection.find(any(core.framework.mongo.Query.class))).thenReturn(List.of(task()));
        when(service.taskCollection.count(any(org.bson.conversions.Bson.class))).thenReturn(1L);
        var request = new PageRequest();
        request.limit = 500;

        var page = service.inbox("user-1", request);

        assertEquals(100, page.limit());
        assertEquals(1, page.total());
        var query = ArgumentCaptor.forClass(Query.class);
        verify(service.taskCollection).find(query.capture());
        var sort = query.getValue().sort.toBsonDocument(
            BsonDocument.class, MongoClientSettings.getDefaultCodecRegistry());
        assertEquals(List.of("priority_rank", "due_at", "updated_at"), List.copyOf(sort.keySet()));
        assertEquals(new BsonInt32(1), sort.get("priority_rank"));
        assertEquals(new BsonInt32(-1), sort.get("updated_at"));
    }

    @Test
    void reportsRejectInvalidCaptureRange() {
        var service = service();
        when(service.merchantService.visibleMerchantIds("user-1")).thenReturn(List.of("merchant-1"));
        var request = new PageRequest();
        request.capturedFrom = "2026-08-18T00:00:00Z";
        request.capturedTo = "2026-08-17T00:00:00Z";

        assertThrows(BadRequestException.class, () -> service.reports("user-1", request));
    }

    @SuppressWarnings("unchecked")
    private SeoOpsQueryService service() {
        var service = new SeoOpsQueryService();
        service.merchantService = mock(SeoMerchantService.class);
        service.taskCollection = mock(MongoCollection.class);
        service.merchantCollection = mock(MongoCollection.class);
        service.locationCollection = mock(MongoCollection.class);
        service.userCollection = mock(MongoCollection.class);
        service.viewMapper = new SeoOpsViewMapper();
        return service;
    }

    private SeoTask task() {
        var task = new SeoTask();
        task.id = "task-1";
        task.status = SeoTaskStatus.DRAFT;
        return task;
    }
}
