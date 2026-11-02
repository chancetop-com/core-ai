package ai.core.server.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaskWebServiceImplTest {
    @Test
    void extractsTypeBeforeTheFirstColon() {
        assertEquals("TRACE_ARCHIVE", TaskWebServiceImpl.extractType("TRACE_ARCHIVE:2026-11-02T00:31"));
        assertEquals("TRACE_DAILY_MAINTENANCE", TaskWebServiceImpl.extractType("TRACE_DAILY_MAINTENANCE:2026-11-02"));
        assertEquals("TRACE_ARCHIVE", TaskWebServiceImpl.extractType("TRACE_ARCHIVE"));
    }
}
