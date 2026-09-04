package ai.core.cli.hub.skill;

import ai.core.utils.JsonUtil;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Read/write of the {@code .skill-hub.json} install marker inside a pulled skill
 * directory. The marker records where a skill came from and which server content it
 * matches ({@code digest}); {@code skill list} derives up-to-date/outdated/modified
 * from comparing it against the local files and the server digest. The dot-prefixed
 * file name keeps it invisible to {@code SkillLoader} scans.
 *
 * @author stephen
 */
public final class SkillHubMarker {
    public static final String FILE_NAME = ".skill-hub.json";
    private static final String QUALIFIED_NAME = "qualified_name";
    private static final String ID = "id";
    private static final String DIGEST = "digest";
    private static final String SERVER = "server";
    private static final String PULLED_AT = "pulled_at";

    public static Marker load(Path skillDir) {
        var file = skillDir.resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) return null;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> data = JsonUtil.fromJson(Map.class, Files.readString(file, StandardCharsets.UTF_8));
            return new Marker(string(data, QUALIFIED_NAME), string(data, ID), string(data, DIGEST),
                    string(data, SERVER), string(data, PULLED_AT));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read skill hub marker: " + file, e);
        }
    }

    public static void write(Path skillDir, Marker marker) {
        var data = new LinkedHashMap<String, Object>();
        data.put(QUALIFIED_NAME, marker.qualifiedName());
        data.put(ID, marker.id());
        data.put(DIGEST, marker.digest());
        data.put(SERVER, marker.server());
        data.put(PULLED_AT, marker.pulledAt());
        try {
            Files.writeString(skillDir.resolve(FILE_NAME), JsonUtil.toJson(data), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write skill hub marker: " + skillDir, e);
        }
    }

    private static String string(Map<String, Object> data, String key) {
        var value = data.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private SkillHubMarker() {
    }

    public record Marker(String qualifiedName, String id, String digest, String server, String pulledAt) {
        public boolean isManaged() {
            return qualifiedName != null && digest != null;
        }
    }
}
