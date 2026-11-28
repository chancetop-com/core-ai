package ai.core.server.workspace;

import java.nio.file.Path;

/**
 * Streaming HTTP contract between core-ai-server and core-ai-sandbox-runtime for workspace cold
 * archive capture, restore, and quiescent probing. Implementations must never buffer the archive
 * into heap memory.
 *
 * @author stephen
 */
public interface WorkspaceArchiveClient {
    QuiescentStatus probeQuiescent(String host, int port);

    CaptureResult captureToFile(String host, int port, CaptureRequest request, Path targetFile);

    RestoreResult restoreFromFile(String host, int port, Path archiveFile, RestoreOptions options);

    record QuiescentStatus(int activeAsyncTasks, int activeTerminals) {
        public boolean quiescent() {
            return activeAsyncTasks <= 0 && activeTerminals <= 0;
        }
    }

    record CaptureRequest(String archiveId, String workspaceId, long generation, String image) {
    }

    record CaptureResult(String sha256, long sizeBytes, long fileCount) {
    }

    record RestoreOptions(long expectedGeneration, String expectedWorkspaceId, boolean allowOverwrite) {
    }

    record RestoreResult(long fileCount, long bytesRestored) {
    }
}
