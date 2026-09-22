package ai.core.sandbox;

import ai.core.agent.ExecutionContext;
import ai.core.tool.ToolCallResult;
import ai.core.tool.tools.ReadFileTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A sandboxed read_file must hand the model an image, not the bytes of one: the runtime's own reader is text
 * only. These tests pin down when this branch takes over, when it stands aside for the regular tool, and that
 * the downloaded payload does not outlive the call.
 *
 * @author stephen
 */
class SandboxImageReaderTest {
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D};
    private static final byte[] BMP = {'B', 'M', 0x36, 0x00, 0x00, 0x00};
    private static final Map<String, Object> IMAGE_ARGS = Map.of("file_path", "/tmp/frame.png");

    @TempDir
    Path tempDir;

    @Test
    void imageBytesAreDeclaredAsAnImage() throws IOException {
        var file = file("frame.png", PNG);
        var sandbox = new StubSandbox(new SandboxFile(file, "frame.png", "image/png", PNG.length));

        var result = SandboxImageReader.tryRead(ReadFileTool.TOOL_NAME, IMAGE_ARGS, sandbox);

        assertNotNull(result);
        assertTrue(result.isCompleted(), result.getResult());
        assertTrue(result.hasImage());
        assertEquals("image/png", result.getImageFormat());
        assertEquals(List.of("/tmp/frame.png"), sandbox.downloads);
        assertFalse(Files.exists(file), "the downloaded payload must not outlive the call");
    }

    @Test
    void bytesThatAreNotAnImageStandAsideForTheTextReader() throws IOException {
        var file = file("probe.png", "<html>not an image</html>".getBytes(StandardCharsets.UTF_8));
        var sandbox = new StubSandbox(new SandboxFile(file, "probe.png", "image/png", Files.size(file)));

        assertNull(SandboxImageReader.tryRead(ReadFileTool.TOOL_NAME, IMAGE_ARGS, sandbox));
        assertFalse(Files.exists(file), "the downloaded payload must not outlive the call");
    }

    @Test
    void unreadableImageFormatIsReportedLikeTheHostTool() throws IOException {
        var file = file("frame.png", BMP);
        var sandbox = new StubSandbox(new SandboxFile(file, "frame.png", "image/bmp", BMP.length));

        var result = SandboxImageReader.tryRead(ReadFileTool.TOOL_NAME, IMAGE_ARGS, sandbox);

        assertNotNull(result);
        assertTrue(result.isFailed());
        assertTrue(result.getResult().contains("model cannot read"), result.getResult());
    }

    @Test
    void oversizedImageIsRejectedBeforeItIsRead() throws IOException {
        var file = file("huge.png", PNG);
        var sandbox = new StubSandbox(new SandboxFile(file, "huge.png", "image/png", 64L * 1024 * 1024));

        var result = SandboxImageReader.tryRead(ReadFileTool.TOOL_NAME, IMAGE_ARGS, sandbox);

        assertNotNull(result);
        assertTrue(result.isFailed());
        assertTrue(result.getResult().contains("too large"), result.getResult());
    }

    @Test
    void aFileTheSandboxCannotServeStandsAside() {
        assertNull(SandboxImageReader.tryRead(ReadFileTool.TOOL_NAME, IMAGE_ARGS, new StubSandbox(null)));
    }

    @Test
    void pathsThatAreNotImagesAreNotDownloaded() {
        var sandbox = new StubSandbox(null);
        Map<String, Object> args = Map.of("file_path", "/tmp/report.csv");

        assertNull(SandboxImageReader.tryRead(ReadFileTool.TOOL_NAME, args, sandbox));
        assertNull(SandboxImageReader.tryRead(ReadFileTool.TOOL_NAME, Map.of(), sandbox));
        assertTrue(sandbox.downloads.isEmpty());
    }

    @Test
    void otherToolsAreNotTouched() {
        var sandbox = new StubSandbox(null);

        assertNull(SandboxImageReader.tryRead("run_bash_command", IMAGE_ARGS, sandbox));
        assertTrue(sandbox.downloads.isEmpty());
    }

    private Path file(String name, byte[] content) throws IOException {
        var path = tempDir.resolve(name);
        Files.write(path, content);
        return path;
    }

    private static final class StubSandbox implements Sandbox {
        private final SandboxFile file;
        private final List<String> downloads = new ArrayList<>();

        StubSandbox(SandboxFile file) {
            this.file = file;
        }

        @Override
        public SandboxFile downloadFile(String path) {
            downloads.add(path);
            if (file == null) throw new IllegalStateException("file not found: " + path);
            return file;
        }

        @Override
        public boolean shouldIntercept(String toolName) {
            return true;
        }

        @Override
        public ToolCallResult execute(String toolName, String arguments, ExecutionContext context) {
            return ToolCallResult.completed("text");
        }

        @Override
        public SandboxStatus getStatus() {
            return SandboxStatus.READY;
        }

        @Override
        public String getId() {
            return "sandbox-1";
        }

        @Override
        public String hostname() {
            return "sandbox-1";
        }

        @Override
        public void materializeSkill(String name, String version, byte[] tarBytes) {
        }

        @Override
        public void uploadFile(String path, byte[] content) {
        }

        @Override
        public String ip() {
            return "127.0.0.1";
        }

        @Override
        public int port() {
            return 8080;
        }

        @Override
        public String image() {
            return "test";
        }

        @Override
        public String startMcpServer(String id, String command, List<String> args, Map<String, String> env, int timeoutSeconds) {
            return id;
        }

        @Override
        public void stopMcpServer(String id) {
        }

        @Override
        public String getMcpEndpoint() {
            return null;
        }

        @Override
        public void bind(SandboxBinding binding) {
        }

        @Override
        public void unbind() {
        }

        @Override
        public void close() {
        }
    }
}
