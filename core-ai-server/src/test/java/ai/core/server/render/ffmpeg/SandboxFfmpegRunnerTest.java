package ai.core.server.render.ffmpeg;

import ai.core.sandbox.Sandbox;
import ai.core.sandbox.SandboxFile;
import ai.core.server.domain.FileRecord;
import ai.core.server.file.FileService;
import ai.core.server.sandbox.SandboxService;
import ai.core.tool.ToolCallResult;
import ai.core.tool.tools.ShellCommandTool;
import ai.core.utils.JsonUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The plan is server-built, but it crosses a shell on the way to ffmpeg — quoting and file-name
 * checks are what keep that crossing honest, and the ffmpeg major is asserted before any step runs.
 *
 * @author stephen
 */
class SandboxFfmpegRunnerTest {
    private static SandboxFfmpegRunner.Plan plan() {
        return new SandboxFfmpegRunner.Plan("job-1", "user-1",
            List.of(new SandboxFfmpegRunner.Download("in.mp4", "https://storage.example.com/take.mp4?sig=abc")),
            List.of(new SandboxFfmpegRunner.Write("concat.txt", "ffconcat version 1.0\n")),
            List.of(List.of("-y", "-i", "in.mp4", "-vf", "scale=1080:1920,setsar=1", "out.mp4")),
            List.of(new SandboxFfmpegRunner.Output("out.mp4", "EPISODE", "video/mp4")),
            600_000L, 1800, 8);
    }

    private SandboxFfmpegRunner runner;
    private FileService fileService;
    private SandboxService sandboxService;

    @BeforeEach
    void createRunner() {
        runner = new SandboxFfmpegRunner();
        fileService = mock(FileService.class);
        sandboxService = mock(SandboxService.class);
        runner.fileService = fileService;
        runner.sandboxService = sandboxService;
    }

    @Test
    void acquireForcesTheLazyContainerUp() {
        var sandbox = mock(Sandbox.class);
        when(sandboxService.createSandbox(any(), eq("job-1"), eq("user-1"))).thenReturn(sandbox);

        assertEquals(sandbox, runner.acquire(plan(), "job-1"));
        // without this the health probe reads ip()=null / port()=0 off an unacquired sandbox
        verify(sandboxService).ensureSandboxReady("job-1");
    }

    @Test
    void acquireFailsWhenNoProviderIsConfigured() {
        when(sandboxService.createSandbox(any(), anyString(), anyString())).thenReturn(null);

        assertThrows(IllegalStateException.class, () -> runner.acquire(plan(), "job-1"));
    }

    @Test
    void ffmpegCommandQuotesEveryArgument() {
        var command = runner.ffmpegCommand(List.of("-i", "in.mp4", "-vf", "drawtext=text='hi there'"));

        assertEquals("ffmpeg '-i' 'in.mp4' '-vf' 'drawtext=text='\\''hi there'\\'''", command,
            "a filter graph carrying spaces or quotes must reach ffmpeg as one argument, not as shell syntax");
    }

    @Test
    void safeNameRejectsPathEscapes() {
        for (var name : new String[]{null, "", "  ", "../etc/passwd", "sub/dir.mp4", "back\\slash.mp4"}) {
            assertThrows(IllegalArgumentException.class, () -> runner.safeName(name), "must reject: " + name);
        }
        assertEquals("out.mp4", runner.safeName("out.mp4"));
    }

    @Test
    void executeDownloadsWritesRendersAndCollectsProducts() {
        var sandbox = mock(Sandbox.class);
        var record = new FileRecord();
        record.id = "file-1";
        when(sandbox.execute(eq(ShellCommandTool.TOOL_NAME), anyString(), any())).thenReturn(ToolCallResult.completed("ok"));
        when(sandbox.downloadFile("/tmp/job/out.mp4")).thenReturn(new SandboxFile(Path.of("out.mp4"), "out.mp4", "video/mp4", 10));
        when(fileService.uploadIfAbsent(eq("user-1"), eq("out.mp4"), eq("video/mp4"), any(Path.class))).thenReturn(record);

        var products = runner.execute(sandbox, plan(), "/tmp/job");

        assertEquals(record, products.get("EPISODE"), "products are keyed by the plan's output kind");
        verify(sandbox).uploadFile(eq("/tmp/job/concat.txt"), any(byte[].class));
        var arguments = ArgumentCaptor.forClass(String.class);
        verify(sandbox, times(3)).execute(eq(ShellCommandTool.TOOL_NAME), arguments.capture(), any());
        var commands = arguments.getAllValues().stream().map(json -> (String) JsonUtil.toMap(json).get("command")).toList();
        assertTrue(commands.get(0).startsWith("mkdir -p "), commands.get(0));
        assertTrue(commands.get(1).contains("curl -fsSL 'https://storage.example.com/take.mp4?sig=abc' -o 'in.mp4'"), commands.get(1));
        assertTrue(commands.get(2).startsWith("ffmpeg '-y' '-i' 'in.mp4'"), commands.get(2));
    }

    @Test
    void executeFailsLoudlyWhenAStepFails() {
        var sandbox = mock(Sandbox.class);
        when(sandbox.execute(eq(ShellCommandTool.TOOL_NAME), anyString(), any())).thenReturn(ToolCallResult.failed("exit 1: no such file"));

        var e = assertThrows(IllegalStateException.class, () -> runner.execute(sandbox, plan(), "/tmp/job"));

        assertTrue(e.getMessage().contains("ffmpeg plan step failed"), e.getMessage());
    }

    @Test
    void refusesAnImageWhoseFfmpegMajorDoesNotMatch() {
        runner.requireFfmpegMajor("{\"status\":\"ok\",\"ffmpeg_major\":8}", 8);

        for (var health : List.of("{\"status\":\"ok\"}", "{\"ffmpeg_major\":7}", "{\"ffmpeg_major\":\"8\"}")) {
            var e = assertThrows(IllegalStateException.class, () -> runner.requireFfmpegMajor(health, 8), health);
            assertTrue(e.getMessage().startsWith("FFMPEG_VERSION_MISMATCH"), e.getMessage());
        }
    }
}
