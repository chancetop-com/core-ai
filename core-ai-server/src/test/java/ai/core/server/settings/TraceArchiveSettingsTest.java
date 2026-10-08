package ai.core.server.settings;

import ai.core.api.server.settings.SystemSettingsRequest;
import ai.core.server.domain.SystemSettings;
import ai.core.server.trace.maintenance.TraceArchivingJob;
import core.framework.mongo.MongoCollection;
import core.framework.web.exception.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TraceArchiveSettingsTest {
    private TraceArchiveSettings settings;
    private MongoCollection<SystemSettings> collection;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        settings = new TraceArchiveSettings();
        collection = mock(MongoCollection.class);
        settings.systemSettingsCollection = collection;
    }

    @Test
    void intervalDefaultsToOneDayUntilConfigured() {
        when(collection.get("default")).thenReturn(Optional.empty());
        assertEquals(TraceArchivingJob.DEFAULT_INTERVAL_MINUTES, settings.intervalMinutes());

        var entity = new SystemSettings();
        entity.traceArchiveIntervalMinutes = 120;
        when(collection.get("default")).thenReturn(Optional.of(entity));
        assertEquals(120, settings.intervalMinutes());
    }

    @Test
    void applyKeepsTheExistingValueWhenTheRequestOmitsTheInterval() {
        var entity = new SystemSettings();
        entity.traceArchiveIntervalMinutes = 120;

        TraceArchiveSettings.apply(entity, new SystemSettingsRequest());
        assertEquals(120, entity.traceArchiveIntervalMinutes.intValue());

        var request = new SystemSettingsRequest();
        request.traceArchiveIntervalMinutes = 60;
        TraceArchiveSettings.apply(entity, request);
        assertEquals(60, entity.traceArchiveIntervalMinutes.intValue());
    }

    @Test
    void validateAcceptsNullAndTheRangeEnds() {
        assertDoesNotThrow(() -> TraceArchiveSettings.validate(new SystemSettingsRequest()));

        var lower = new SystemSettingsRequest();
        lower.traceArchiveIntervalMinutes = TraceArchiveSettings.MIN_INTERVAL_MINUTES;
        assertDoesNotThrow(() -> TraceArchiveSettings.validate(lower));

        var upper = new SystemSettingsRequest();
        upper.traceArchiveIntervalMinutes = TraceArchiveSettings.MAX_INTERVAL_MINUTES;
        assertDoesNotThrow(() -> TraceArchiveSettings.validate(upper));
    }

    @Test
    void validateRejectsOutOfRangeIntervals() {
        var tooSmall = new SystemSettingsRequest();
        tooSmall.traceArchiveIntervalMinutes = TraceArchiveSettings.MIN_INTERVAL_MINUTES - 1;
        assertThrows(BadRequestException.class, () -> TraceArchiveSettings.validate(tooSmall));

        var tooLarge = new SystemSettingsRequest();
        tooLarge.traceArchiveIntervalMinutes = TraceArchiveSettings.MAX_INTERVAL_MINUTES + 1;
        assertThrows(BadRequestException.class, () -> TraceArchiveSettings.validate(tooLarge));
    }
}
