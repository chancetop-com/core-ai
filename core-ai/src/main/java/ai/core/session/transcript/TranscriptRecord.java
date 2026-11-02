package ai.core.session.transcript;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One line of a session transcript. Common fields are stamped by the recorder; every other field is
 * the event payload. Payload names are kept verbatim so a record and its live event carry the same keys.
 *
 * @author stephen
 */
public class TranscriptRecord {
    private static final Set<String> COMMON_KEYS = Set.of("v", "seq", "ts", "turn_id", "kind", "synthetic");

    public int version = 1;
    public long seq;
    public long ts;
    public String turnId;
    public String kind;
    public Boolean synthetic;

    private final Map<String, Object> payload = new LinkedHashMap<>();

    public static TranscriptRecord fromMap(Map<String, Object> map) {
        var record = new TranscriptRecord();
        record.version = intValue(map.get("v"), 1);
        record.seq = longValue(map.get("seq"));
        record.ts = longValue(map.get("ts"));
        record.turnId = map.get("turn_id") == null ? null : String.valueOf(map.get("turn_id"));
        record.kind = map.get("kind") == null ? null : String.valueOf(map.get("kind"));
        record.synthetic = map.get("synthetic") instanceof Boolean flag ? flag : null;
        record.payload.putAll(map);
        COMMON_KEYS.forEach(record.payload::remove);
        return record;
    }

    public Map<String, Object> toMap() {
        var map = new LinkedHashMap<String, Object>();
        map.put("v", version);
        map.put("seq", seq);
        map.put("ts", ts);
        if (turnId != null) map.put("turn_id", turnId);
        if (kind != null) map.put("kind", kind);
        if (synthetic != null) map.put("synthetic", synthetic);
        map.putAll(payload);
        return map;
    }

    public Map<String, Object> payload() {
        return payload;
    }

    public TranscriptRecord put(String name, Object value) {
        if (value != null) {
            payload.put(name, value);
        }
        return this;
    }

    public Object get(String name) {
        return payload.get(name);
    }

    public String text(String name) {
        var value = payload.get(name);
        return value == null ? null : String.valueOf(value);
    }

    public Long number(String name) {
        var value = payload.get(name);
        if (value instanceof Number number) return number.longValue();
        if (value instanceof String text) {
            try {
                return Long.valueOf(text);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    public Double decimal(String name) {
        var value = payload.get(name);
        if (value instanceof Number number) return number.doubleValue();
        if (value instanceof String text) {
            try {
                return Double.valueOf(text);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    public Boolean bool(String name) {
        var value = payload.get(name);
        if (value instanceof Boolean flag) return flag;
        if (value instanceof String text) return Boolean.valueOf(text);
        return null;
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> objects(String name) {
        var value = payload.get(name);
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : null;
    }

    private static long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : 0;
    }

    private static int intValue(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }
}
