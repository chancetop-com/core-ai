package ai.core.cli.hub.skill;

import ai.core.utils.JsonUtil;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Read/write of the {@code .skill-hub.json} marker inside a hub-linked skill directory.
 * The marker records which hub skill a local directory belongs to ({@code qualified_name},
 * {@code id}, {@code server}) and which server content it matches ({@code digest}); it is
 * written by {@code skill pull} (pulled_at) and by {@code skill push} (pushed_at), so a
 * pushed working copy is the same install a pull would otherwise create. {@code skill list}
 * derives up-to-date/outdated/modified from comparing it against the local files and the
 * server digest. The dot-prefixed file name keeps it invisible to {@code SkillLoader} scans.
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
    private static final String PUSHED_AT = "pushed_at";

    public static Marker load(Path skillDir) {
        var file = skillDir.resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) return null;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> data = JsonUtil.fromJson(Map.class, Files.readString(file, StandardCharsets.UTF_8));
            return new Marker(string(data, QUALIFIED_NAME), string(data, ID), string(data, DIGEST),
                    string(data, SERVER), string(data, PULLED_AT), string(data, PUSHED_AT));
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
        data.put(PUSHED_AT, marker.pushedAt());
        try {
            Files.writeString(skillDir.resolve(FILE_NAME), JsonUtil.toJson(data), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write skill hub marker: " + skillDir, e);
        }
    }

    /**
     * Links a directory to the hub skill an upload created, from the upload response.
     * Best effort: a push must not fail over the marker, so unparsable responses, missing
     * fields and read-only directories all report false instead of throwing.
     *
     * @return true when the marker was written
     */
    public static boolean linkAfterPush(Path skillDir, String responseBody, String serverUrl, String digest) {
        if (skillDir == null || responseBody == null || digest == null) return false;
        var id = responseValue(responseBody, "id");
        var qualifiedName = responseValue(responseBody, "qualified_name");
        if (id == null || qualifiedName == null) return false;
        try {
            write(skillDir, Marker.pushed(qualifiedName, id, digest, serverUrl));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Trailing-slash-insensitive form, so markers written by different clients still match the same hub. */
    static String normalizeServer(String serverUrl) {
        if (serverUrl == null) return null;
        return serverUrl.endsWith("/") ? serverUrl.substring(0, serverUrl.length() - 1) : serverUrl;
    }

    private static String now() {
        return ZonedDateTime.now().toInstant().toString();
    }

    private static String responseValue(String responseBody, String key) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = JsonUtil.fromJson(Map.class, responseBody);
            var value = body.get(key);
            return value == null ? null : String.valueOf(value);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String string(Map<String, Object> data, String key) {
        var value = data.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private SkillHubMarker() {
    }

    public record Marker(String qualifiedName, String id, String digest, String server, String pulledAt, String pushedAt) {
        public boolean isManaged() {
            return qualifiedName != null && digest != null;
        }

        /** Marker of a directory this machine pulled from the hub. */
        public static Marker pulled(String qualifiedName, String id, String digest, String server) {
            return new Marker(qualifiedName, id, digest, normalizeServer(server), now(), null);
        }

        /** Marker of a directory this machine pushed to the hub — the push linked it as this skill's local copy. */
        public static Marker pushed(String qualifiedName, String id, String digest, String server) {
            return new Marker(qualifiedName, id, digest, normalizeServer(server), null, now());
        }
    }
}
