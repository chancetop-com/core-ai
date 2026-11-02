package ai.core.session.transcript;

import ai.core.agent.AttachedContent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared payload helpers: inline small text, externalize large text into blobs and keep the same
 * field names as the record format.
 *
 * @author stephen
 */
final class TranscriptPayloads {
    private static final int MAX_TEXT = 32 * 1024;
    private static final int PREVIEW = 4 * 1024;

    static void putText(TranscriptStore store, TranscriptRecord record, String field, String value) {
        if (value == null) return;
        if (value.length() <= MAX_TEXT) {
            record.put(field, value);
            return;
        }
        record.put(field + "_ref", store.writeBlob(record.seq, field, 0, value));
        record.put(field + "_preview", value.substring(0, PREVIEW));
    }

    static void putInput(TranscriptStore store, TranscriptRecord record, Map<String, Object> input,
                         String text, int index) {
        if (text == null) return;
        if (text.length() <= MAX_TEXT) {
            input.put("text", text);
            return;
        }
        input.put("text_ref", store.writeBlob(record.seq, "inputs", index, text));
        input.put("text_preview", text.substring(0, PREVIEW));
    }

    static List<Map<String, Object>> attachments(TranscriptStore store, long seq,
                                                 List<AttachedContent> attachments) {
        var out = new ArrayList<Map<String, Object>>();
        for (var attachment : attachments) {
            var item = new LinkedHashMap<String, Object>();
            item.put("filename", attachment.filename);
            item.put("mediaType", attachment.mediaType);
            if (attachment.data != null) {
                item.put("size", (long) attachment.data.length());
                item.put("blob_ref", store.writeBlob(seq, "attachments", out.size(), attachment.data));
            } else {
                item.put("url", attachment.url);
            }
            out.add(item);
        }
        return out;
    }

    private TranscriptPayloads() {
    }
}
