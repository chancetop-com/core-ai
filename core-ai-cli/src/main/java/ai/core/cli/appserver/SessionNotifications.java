package ai.core.cli.appserver;

import ai.core.session.transcript.TranscriptRecord;
import ai.core.utils.JsonUtil;

/** Pushes a recorded transcript item to the connected app-server client. */
final class SessionNotifications {
    static void notifyRecord(NotificationSink sink, String sessionId, TranscriptRecord record) {
        var params = Params.object();
        params.put("sessionId", sessionId);
        params.set("record", JsonUtil.OBJECT_MAPPER.valueToTree(record));
        sink.notify("session/record", params);
    }

    private SessionNotifications() {
    }
}
