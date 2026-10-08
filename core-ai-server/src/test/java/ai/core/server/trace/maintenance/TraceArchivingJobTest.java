package ai.core.server.trace.maintenance;

import ai.core.server.domain.BackgroundTask;
import ai.core.server.settings.TraceArchiveSettings;
import ai.core.server.task.TaskRunner;
import core.framework.scheduler.JobContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TraceArchivingJobTest {
    private static final DateTimeFormatter DUE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm").withZone(ZoneId.of("UTC"));

    private TraceArchivingJob job;
    private TaskRunner taskRunner;
    private TraceArchiveSettings archiveSettings;
    private TraceArchivingTask task;

    @BeforeEach
    void setUp() {
        job = new TraceArchivingJob();
        taskRunner = mock(TaskRunner.class);
        archiveSettings = mock(TraceArchiveSettings.class);
        task = mock(TraceArchivingTask.class);
        job.taskRunner = taskRunner;
        job.archiveSettings = archiveSettings;
        job.task = task;
    }

    @Test
    void skipsWhileWithinTheInterval() {
        var last = ZonedDateTime.now().minusMinutes(60);
        when(archiveSettings.intervalMinutes()).thenReturn(1440);
        when(taskRunner.list(TraceArchivingTask.TYPE, 1)).thenReturn(List.of(record(last)));

        job.execute(mock(JobContext.class));

        verify(taskRunner, never()).run(eq(task), anyString());
    }

    @Test
    void runsWithTheDueSlotAsTaskIdOnceTheIntervalPassed() {
        var last = ZonedDateTime.now().minusHours(25);
        when(archiveSettings.intervalMinutes()).thenReturn(1440);
        when(taskRunner.list(TraceArchivingTask.TYPE, 1)).thenReturn(List.of(record(last)));

        job.execute(mock(JobContext.class));

        var taskId = ArgumentCaptor.forClass(String.class);
        verify(taskRunner).run(eq(task), taskId.capture());
        assertEquals(TraceArchivingTask.TYPE + ":" + DUE_FORMAT.format(last.plusMinutes(1440)), taskId.getValue());
    }

    @Test
    void runsOnFirstEverCheck() {
        when(archiveSettings.intervalMinutes()).thenReturn(1440);
        when(taskRunner.list(TraceArchivingTask.TYPE, 1)).thenReturn(List.of());

        job.execute(mock(JobContext.class));

        verify(taskRunner).run(eq(task), anyString());
    }

    @Test
    void dueTaskIdHoldsTheRunWithinTheIntervalAndItsTolerance() {
        var last = ZonedDateTime.parse("2026-10-08T00:00:00Z");

        assertEquals("TRACE_ARCHIVE:2026-10-08T01:00", TraceArchivingJob.dueTaskId(last, 60, last.plusMinutes(60)));
        assertEquals("TRACE_ARCHIVE:2026-10-08T01:00", TraceArchivingJob.dueTaskId(last, 60, last.plusMinutes(60).minusSeconds(29)));
        assertNull(TraceArchivingJob.dueTaskId(last, 60, last.plusMinutes(60).minusSeconds(31)));
    }

    @Test
    void dueTaskIdLabelsCatchUpRunsWithTheFirstMissedSlot() {
        var last = ZonedDateTime.parse("2026-10-01T00:00:00Z");

        assertEquals("TRACE_ARCHIVE:2026-10-01T01:00", TraceArchivingJob.dueTaskId(last, 60, ZonedDateTime.parse("2026-10-08T12:00:00Z")));
    }

    private BackgroundTask record(ZonedDateTime startedAt) {
        var record = new BackgroundTask();
        record.id = TraceArchivingTask.TYPE + ":test";
        record.startedAt = startedAt;
        return record;
    }
}
