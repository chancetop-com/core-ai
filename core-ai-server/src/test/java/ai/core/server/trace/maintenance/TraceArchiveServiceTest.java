package ai.core.server.trace.maintenance;

import ai.core.server.blob.ObjectStorageServiceResolver;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TraceArchiveServiceTest {
    @Test
    void archivesLandInThePrivateArtifactContainerByDefault() {
        var resolver = mock(ObjectStorageServiceResolver.class);
        when(resolver.artifactContainer()).thenReturn("artifacts");
        var service = new TraceArchiveService(resolver, null, null);

        assertEquals("artifacts", service.archiveContainer());
        verify(resolver, never()).multimodalContainer();
    }

    @Test
    void blankConfiguredContainerStillFallsBackToThePrivateArtifactContainer() {
        var resolver = mock(ObjectStorageServiceResolver.class);
        when(resolver.artifactContainer()).thenReturn("artifacts");
        var service = new TraceArchiveService(resolver, "  ", null);

        assertEquals("artifacts", service.archiveContainer());
    }

    @Test
    void configuredContainerWins() {
        var resolver = mock(ObjectStorageServiceResolver.class);
        var service = new TraceArchiveService(resolver, "custom-archive", null);

        assertEquals("custom-archive", service.archiveContainer());
        verify(resolver, never()).artifactContainer();
    }
}
