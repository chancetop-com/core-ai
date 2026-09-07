package ai.core.server.render.ffmpeg;

import ai.core.server.domain.FileRecord;
import ai.core.server.file.FileService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The local runner honours the same contract as the sandbox one: version pin, plan file discipline,
 * outputs filed through FileService, nothing left behind on disk.
 *
 * @author stephen
 */
class LocalFfmpegRunnerTest {
    /** A runner whose binary probe is fixed, so the plan flow can be exercised without ffmpeg installed. */
    private static LocalFfmpegRunner runnerReporting(int major) {
        return new LocalFfmpegRunner("ffmpeg") {
            @Override
            public int detectMajor() {
                return major;
            }
        };
    }

    @Test
    void parsesTheMajorOutOfTheVersionBanner() {
        assertEquals(8, FfmpegRunner.parseFfmpegMajor("ffmpeg version 8.0.1-full_build-www.gyan.dev Copyright (c) 2000-2025 the FFmpeg developers\nbuilt with gcc"));
        assertEquals(7, FfmpegRunner.parseFfmpegMajor("ffmpeg version n7.1-3-g2e5b8e4 Copyright"));
        assertEquals(6, FfmpegRunner.parseFfmpegMajor("ffmpeg version 6.1.1 Copyright (c) 2000-2023"));
        assertEquals(0, FfmpegRunner.parseFfmpegMajor("bash: ffmpeg: command not found"));
        assertEquals(0, FfmpegRunner.parseFfmpegMajor(null));
    }

    @Test
    void refusesAMissingOrDifferentBinaryBeforeTouchingDisk() {
        var missing = runnerReporting(0);
        var e = assertThrows(IllegalStateException.class, () -> missing.requireFfmpegMajor(8));
        assertTrue(e.getMessage().startsWith("FFMPEG_VERSION_MISMATCH"), e.getMessage());
        assertTrue(e.getMessage().contains("none"), e.getMessage());

        var older = runnerReporting(6);
        assertTrue(assertThrows(IllegalStateException.class, () -> older.requireFfmpegMajor(8)).getMessage().contains("reports 6"));
        runnerReporting(8).requireFfmpegMajor(8);
    }

    @Test
    void writesPlanFilesFilesOutputsAndCleansUp() throws Exception {
        var runner = runnerReporting(8);
        var files = mock(FileService.class);
        runner.fileService = files;
        var record = new FileRecord();
        record.id = "file-1";
        var captor = ArgumentCaptor.forClass(Path.class);
        when(files.uploadIfAbsent(eq("user-1"), eq("concat.txt"), eq("text/plain"), any(Path.class))).thenReturn(record);

        var plan = new FfmpegRunner.Plan("job-1", "user-1", List.of(),
            List.of(new FfmpegRunner.Write("concat.txt", "ffconcat version 1.0\n")),
            List.of(), List.of(new FfmpegRunner.Output("concat.txt", "EPISODE", "text/plain")),
            60_000L, 1800, 8);
        var products = runner.run(plan);

        assertEquals("file-1", products.get("EPISODE").file().id);
        verify(files).uploadIfAbsent(eq("user-1"), eq("concat.txt"), eq("text/plain"), captor.capture());
        var uploaded = captor.getValue();
        assertTrue(uploaded.getParent().getFileName().toString().startsWith("ffmpeg-plan-"));
        assertFalse(Files.exists(uploaded.getParent()), "the per-job work dir is removed after the plan");
    }

    @Test
    void missingOutputIsAnErrorNotASilentSuccess() {
        var runner = runnerReporting(8);
        runner.fileService = mock(FileService.class);
        var plan = new FfmpegRunner.Plan("job-2", "user-1", List.of(), List.of(), List.of(),
            List.of(new FfmpegRunner.Output("output.mp4", "EPISODE", "video/mp4")), 60_000L, 1800, 8);
        var e = assertThrows(IllegalStateException.class, () -> runner.run(plan));
        assertTrue(e.getMessage().contains("produced no output.mp4"), e.getMessage());
    }

    @Test
    void planFileNamesMayNotEscapeTheWorkDir() {
        for (var name : new String[]{"../x.mp4", "a/b.mp4", "a\\b.mp4", "", null}) {
            assertThrows(IllegalArgumentException.class, () -> FfmpegRunner.safeName(name), String.valueOf(name));
        }
        assertEquals("E9FC.mp4", FfmpegRunner.safeName("E9FC.mp4"));
    }

    @Test
    void probeStepsRunFfprobeNextToTheConfiguredFfmpeg() {
        assertEquals("ffprobe", LocalFfmpegRunner.probeBinary("ffmpeg"));
        assertEquals("/opt/ffmpeg/bin/ffprobe", LocalFfmpegRunner.probeBinary("/opt/ffmpeg/bin/ffmpeg"));
        assertEquals("C:/tools/ffprobe.exe", LocalFfmpegRunner.probeBinary("C:/tools/ffmpeg.exe"));
        assertEquals("ffprobe", LocalFfmpegRunner.probeBinary("/weird/path/to/binary"));
        assertEquals("ffprobe", FfmpegRunner.binaryFor(List.of("ffprobe", "-v", "error", "in.mp4")));
        assertEquals(List.of("-v", "error", "in.mp4"), FfmpegRunner.arguments(List.of("ffprobe", "-v", "error", "in.mp4")));
        assertEquals("ffmpeg", FfmpegRunner.binaryFor(List.of("-y", "-i", "in.mp4", "out.mp4")));
    }
}
