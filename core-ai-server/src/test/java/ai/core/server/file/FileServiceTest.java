package ai.core.server.file;

import ai.core.server.domain.FileRecord;
import core.framework.mongo.MongoCollection;
import core.framework.web.exception.ForbiddenException;
import core.framework.web.exception.NotFoundException;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FileServiceTest {
    @Test
    void shareCreatesToken() {
        var service = new FileService();
        service.fileRecordCollection = fileRecordCollection();
        var record = file("file-1");
        when(service.fileRecordCollection.get("file-1")).thenReturn(Optional.of(record));
        when(service.fileRecordCollection.update(any(Bson.class), any(Bson.class))).thenReturn(1L);

        var shared = service.share("file-1", "user-1");

        assertSame(record, shared);
        assertNotNull(shared.shareToken);
        assertNotNull(shared.sharedAt);
        verify(service.fileRecordCollection).update(any(Bson.class), any(Bson.class));
    }

    @Test
    void shareReusesExistingToken() {
        var service = new FileService();
        service.fileRecordCollection = fileRecordCollection();
        var record = file("file-1");
        record.shareToken = "existing-token";
        record.sharedAt = ZonedDateTime.now();
        when(service.fileRecordCollection.get("file-1")).thenReturn(Optional.of(record));

        var shared = service.share("file-1", "user-1");

        assertEquals("existing-token", shared.shareToken);
        verify(service.fileRecordCollection, never()).update(any(Bson.class), any(Bson.class));
    }

    @Test
    void shareReturnsTokenCreatedByConcurrentRequest() {
        var service = new FileService();
        service.fileRecordCollection = fileRecordCollection();
        var record = file("file-1");
        var latest = file("file-1");
        latest.shareToken = "concurrent-token";
        latest.sharedAt = ZonedDateTime.now();
        when(service.fileRecordCollection.get("file-1")).thenReturn(Optional.of(record)).thenReturn(Optional.of(latest));
        when(service.fileRecordCollection.update(any(Bson.class), any(Bson.class))).thenReturn(0L);

        var shared = service.share("file-1", "user-1");

        assertEquals("concurrent-token", shared.shareToken);
    }

    @Test
    void shareRejectsNonOwner() {
        var service = new FileService();
        service.fileRecordCollection = fileRecordCollection();
        var record = file("file-1");
        record.userId = "user-2";
        when(service.fileRecordCollection.get("file-1")).thenReturn(Optional.of(record));

        assertThrows(ForbiddenException.class, () -> service.share("file-1", "user-1"));
        verify(service.fileRecordCollection, never()).update(any(Bson.class), any(Bson.class));
    }

    @Test
    void getSharedFindsByToken() {
        var service = new FileService();
        service.fileRecordCollection = fileRecordCollection();
        var record = file("file-1");
        when(service.fileRecordCollection.findOne(any(Bson.class))).thenReturn(Optional.of(record));

        assertSame(record, service.getShared("token"));
    }

    @Test
    void getSharedThrowsForMissingToken() {
        var service = new FileService();
        service.fileRecordCollection = fileRecordCollection();
        when(service.fileRecordCollection.findOne(any(Bson.class))).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> service.getShared("missing"));
    }

    @SuppressWarnings("unchecked")
    private MongoCollection<FileRecord> fileRecordCollection() {
        return (MongoCollection<FileRecord>) mock(MongoCollection.class);
    }

    private FileRecord file(String id) {
        var record = new FileRecord();
        record.id = id;
        record.userId = "user-1";
        record.fileName = "report.html";
        record.size = 128L;
        record.createdAt = ZonedDateTime.now();
        return record;
    }
}
