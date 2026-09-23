package ai.core.server.gateway;

import ai.core.api.server.media.ListMediaJobsRequest;
import ai.core.server.domain.MediaJob;
import ai.core.server.domain.MediaJobInput;
import ai.core.server.domain.User;
import core.framework.mongo.MongoCollection;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author Stephen
 */
class MediaJobWebServiceImplTest {
    private MediaJobWebServiceImpl service;
    private MediaJobService mediaJobService;
    private MongoCollection<User> userCollection;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = new MediaJobWebServiceImpl();
        mediaJobService = mock(MediaJobService.class);
        userCollection = (MongoCollection<User>) mock(MongoCollection.class);
        service.mediaJobService = mediaJobService;
        service.userCollection = userCollection;
        when(userCollection.find(any(Bson.class))).thenReturn(List.of());
    }

    @Test
    void listResolvesOwnerDisplayNames() {
        when(mediaJobService.list(anyInt(), anyInt(), any(), any(), any()))
            .thenReturn(new MediaJobService.MediaJobList(2, List.of(job("job-1", "user-1"), job("job-2", "user-2"))));
        when(userCollection.find(any(Bson.class))).thenReturn(List.of(user("user-1", "Alice")));

        var response = service.list(new ListMediaJobsRequest());

        assertEquals(2, response.total);
        assertEquals("Alice", response.jobs.getFirst().userName);
        assertNull(response.jobs.get(1).userName);
        verify(userCollection).find(any(Bson.class));
    }

    @Test
    void listKeepsAnonymousJobsWithoutUserLookup() {
        when(mediaJobService.list(anyInt(), anyInt(), any(), any(), any()))
            .thenReturn(new MediaJobService.MediaJobList(1, List.of(job("job-1", null))));

        var response = service.list(new ListMediaJobsRequest());

        assertNull(response.jobs.getFirst().userName);
        verify(userCollection, never()).find(any(Bson.class));
    }

    @Test
    void listExposesTheGenerationRecipe() {
        var job = job("job-1", "user-1");
        job.prompt = "make it snow";
        job.requestedSize = "1024x1024";
        job.requestedQuality = "high";
        job.requestedCount = 2;
        job.outputFormat = "jpeg";
        job.outputCompression = 80;
        job.background = "transparent";
        job.inputs = List.of(input());
        when(mediaJobService.list(anyInt(), anyInt(), any(), any(), any()))
            .thenReturn(new MediaJobService.MediaJobList(1, List.of(job)));

        var view = service.list(new ListMediaJobsRequest()).jobs.getFirst();

        assertEquals("1024x1024", view.requestedSize);
        assertEquals("high", view.requestedQuality);
        assertEquals(2, view.requestedCount);
        assertEquals("jpeg", view.outputFormat);
        assertEquals(80, view.outputCompression);
        assertEquals("transparent", view.background);
        var input = view.inputs.getFirst();
        assertEquals("subject", input.role);
        assertEquals("char_lin", input.name);
        assertEquals("job-0", input.jobId);
        assertEquals("file-0", input.fileId);
    }

    @Test
    void listReturnsEmptyInputsWhenTheJobHasNone() {
        when(mediaJobService.list(anyInt(), anyInt(), any(), any(), any()))
            .thenReturn(new MediaJobService.MediaJobList(1, List.of(job("job-1", "user-1"))));

        assertEquals(List.of(), service.list(new ListMediaJobsRequest()).jobs.getFirst().inputs);
    }

    private MediaJobInput input() {
        var input = new MediaJobInput();
        input.kind = MediaJobInput.KIND_MEDIA;
        input.name = "char_lin";
        input.role = "subject";
        input.modality = "image";
        input.jobId = "job-0";
        input.fileId = "file-0";
        input.contentType = "image/png";
        return input;
    }

    private MediaJob job(String id, String userId) {
        var job = new MediaJob();
        job.id = id;
        job.userId = userId;
        return job;
    }

    private User user(String id, String name) {
        var user = new User();
        user.id = id;
        user.name = name;
        return user;
    }
}
