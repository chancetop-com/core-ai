package ai.core.session.transcript;

import java.util.ArrayList;
import java.util.List;

/**
 * The windows a rewind leaves behind: every turn whose start seq falls between {@code to_seq} and
 * the rewind record itself is dropped. Turns appended after the rewind record are new history and
 * stay visible — that boundary is what makes a rewound transcript replayable.
 *
 * @author stephen
 */
final class TranscriptRewind {
    static TranscriptRewind of(List<TranscriptRecord> records) {
        var windows = new ArrayList<Window>();
        for (var record : records) {
            if (!TranscriptKinds.REWIND.equals(record.kind)) continue;
            var toSeq = record.number("to_seq");
            if (toSeq != null) windows.add(new Window(toSeq, record.seq));
        }
        return new TranscriptRewind(windows);
    }

    static long turnSeq(String turnId) {
        if (turnId == null || !turnId.startsWith("t")) return -1;
        try {
            return Long.parseLong(turnId.substring(1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private final List<Window> windows;

    private TranscriptRewind(List<Window> windows) {
        this.windows = windows;
    }

    boolean covers(String turnId) {
        var seq = turnSeq(turnId);
        if (seq < 0) return false;
        for (var window : windows) {
            if (window.covers(seq)) return true;
        }
        return false;
    }

    record Window(long fromSeq, long rewindSeq) {
        boolean covers(long turnSeq) {
            return turnSeq >= fromSeq && turnSeq < rewindSeq;
        }
    }
}
