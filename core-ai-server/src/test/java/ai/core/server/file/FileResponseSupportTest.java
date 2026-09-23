package ai.core.server.file;

import ai.core.server.domain.FileRecord;
import core.framework.api.http.HTTPStatus;
import core.framework.http.HTTPHeaders;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FileResponseSupportTest {
    @Test
    void redirectsToPresignedUrlWithoutCaching() {
        var record = new FileRecord();
        record.size = 128L;
        var fileService = mock(FileService.class);
        when(fileService.downloadUrl(record)).thenReturn("https://blob.example.com/artifacts/file-1.pdf?sv=2018-11-09&se=2026-08-11T03:00:00Z");

        var response = FileResponseSupport.content(record, fileService);

        assertEquals(HTTPStatus.TEMPORARY_REDIRECT, response.status());
        assertEquals("no-store", response.header(HTTPHeaders.CACHE_CONTROL).orElseThrow());
        assertTrue(response.header("ETag").isEmpty());
    }

    @Test
    void servesThumbnailWithLongCache() {
        var record = new FileRecord();
        record.id = "file-1";
        record.size = 128L;
        var fileService = mock(FileService.class);
        when(fileService.thumbnail(record)).thenReturn(new byte[]{1, 2, 3});

        var response = FileResponseSupport.thumbnail(record, fileService);

        assertEquals(HTTPStatus.OK, response.status());
        assertEquals("image/jpeg", response.contentType().orElseThrow().mediaType);
        assertEquals("public, max-age=604800", response.header(HTTPHeaders.CACHE_CONTROL).orElseThrow());
        assertEquals("\"file-1-thumb\"", response.header("ETag").orElseThrow());
    }

    @Test
    void fallsBackToOriginalWhenNoThumbnail() {
        var record = new FileRecord();
        record.size = 128L;
        var fileService = mock(FileService.class);
        when(fileService.thumbnail(record)).thenReturn(null);
        when(fileService.downloadUrl(record)).thenReturn("https://blob.example.com/artifacts/file-1.png?sig=x");

        var response = FileResponseSupport.thumbnail(record, fileService);

        assertEquals(HTTPStatus.TEMPORARY_REDIRECT, response.status());
        assertEquals("no-store", response.header(HTTPHeaders.CACHE_CONTROL).orElseThrow());
    }
}
