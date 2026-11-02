package ai.core.session;

import ai.core.agent.subagent.SubagentPersistenceKeys;
import ai.core.session.transcript.TranscriptStore;
import ai.core.utils.AtomicFiles;
import core.framework.util.Files;

import java.io.File;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * @author stephen
 */
public class FileSessionPersistence implements SessionPersistence {
    private final String directory;

    public FileSessionPersistence(String directory) {
        this.directory = directory;
        Files.createDir(Paths.get(directory));
    }

    @Override
    public void save(String id, String context) {
        AtomicFiles.writeString(Paths.get(path(id)), context);
    }

    @Override
    public void clear() {
        Files.deleteDir(Paths.get(directory));
    }

    @Override
    public void delete(List<String> ids) {
        for (var id : ids) {
            Files.delete(Paths.get(path(id)));
            deleteSubagentFiles(id);
            TranscriptStore.delete(Paths.get(directory), id);
        }
    }

    // Subagent thread files live next to their session and go when the session goes; only the
    // session file itself may be absent (an id that never had a thread or a title-only entry).
    private void deleteSubagentFiles(String id) {
        SessionFileNames.check(id);
        var dir = new File(directory);
        var threadFiles = dir.listFiles((d, name) -> name.startsWith(id + SubagentPersistenceKeys.THREAD_INFIX) && name.endsWith(".data"));
        if (threadFiles == null) return;
        for (var file : threadFiles) {
            Files.delete(file.toPath());
        }
    }

    @Override
    public Optional<String> load(String id) {
        var path = Paths.get(path(id));
        if (!java.nio.file.Files.exists(path)) return Optional.empty();
        return Optional.of(Files.text(path));
    }

    @Override
    public boolean exists(String id) {
        return java.nio.file.Files.exists(Paths.get(path(id)));
    }

    @Override
    public List<SessionInfo> listSessions() {
        var dir = new File(directory);
        var files = dir.listFiles((d, name) -> name.endsWith(".data") && !name.contains(SubagentPersistenceKeys.THREAD_INFIX));
        if (files == null || files.length == 0) return List.of();
        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return Arrays.stream(files)
            .map(f -> {
                var name = f.getName();
                var id = name.substring(0, name.length() - ".data".length());
                var lastModified = Instant.ofEpochMilli(f.lastModified());
                return new SessionInfo(id, lastModified);
            })
            .toList();
    }

    private String path(String id) {
        SessionFileNames.check(id);
        return directory + "/" + id + ".data";
    }
}
