package ai.core.cli.appserver;

import ai.core.session.SessionLock;
import ai.core.session.transcript.TranscriptStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;

/** Removes sidecars left by a crashed process; a file without a snapshot or a live lock is a leftover. */
final class SessionRetention {
    private static final Logger LOGGER = LoggerFactory.getLogger(SessionRetention.class);

    static void retain(EngineBootstrap bootstrap, SessionTitleStore titles, EngineSessionModels models) {
        var infos = bootstrap.sessionManager.listSessions();
        var titleIds = titles.ids();
        var modelIds = models.ids();
        Set<String> liveIds = new HashSet<>(infos.size() + titleIds.size() + modelIds.size());
        for (var info : infos) {
            liveIds.add(info.id());
        }
        for (var id : titleIds) {
            if (SessionLock.isHeld(bootstrap.sessionsDir, id)) liveIds.add(id);
        }
        for (var id : modelIds) {
            if (SessionLock.isHeld(bootstrap.sessionsDir, id)) liveIds.add(id);
        }
        titles.retainOnly(liveIds);
        models.retainOnly(liveIds);
        retainTranscripts(bootstrap);
    }

    private static void retainTranscripts(EngineBootstrap bootstrap) {
        try (var stream = Files.list(bootstrap.sessionsDir)) {
            for (var path : stream.toList()) {
                var fileName = path.getFileName();
                if (fileName == null) continue;
                var id = transcriptId(fileName.toString());
                if (id == null) continue;
                if (bootstrap.sessionPersistence.exists(id) || SessionLock.isHeld(bootstrap.sessionsDir, id)) continue;
                TranscriptStore.delete(bootstrap.sessionsDir, id);
            }
        } catch (IOException e) {
            LOGGER.warn("failed to clean transcript sidecars: {}", e.getMessage());
        }
    }

    private static String transcriptId(String name) {
        if (name.endsWith(".events.jsonl")) return name.substring(0, name.length() - ".events.jsonl".length());
        if (name.endsWith(".blobs")) return name.substring(0, name.length() - ".blobs".length());
        return null;
    }

    private SessionRetention() {
    }
}
