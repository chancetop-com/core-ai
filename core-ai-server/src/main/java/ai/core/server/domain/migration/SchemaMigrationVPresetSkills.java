package ai.core.server.domain.migration;

import core.framework.mongo.Mongo;
import org.bson.Document;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * Seeds the standalone prompt-preset skills — the prompt equivalents of professional video
 * platforms' built-in capabilities (character sheets, scene sheets, contact sheets, camera
 * language, lighting/looks, cover posters). Each is an INDEPENDENT skill so any agent can mount
 * exactly what it needs; converge-on-deploy upserts keep them in sync with the shipped resources.
 *
 * @author stephen
 */
public class SchemaMigrationVPresetSkills implements SchemaMigration {
    static final String[] SKILLS = {
        "ai-character-sheet", "ai-scene-sheet", "ai-contact-sheet",
        "ai-camera-language", "ai-shot-language", "ai-lighting-looks", "ai-cover-poster"};

    static String skillId(String name) {
        return "skill-" + name;
    }

    @Override
    public String version() {
        // 20260829006: initial seed
        // 20260830001: camera-language upgraded to 24-move dictionary; added ai-shot-language (shot sizes/angles/composition/shot-reverse-shot)
        return "20260830003";
    }

    @Override
    public String description() {
        return "seed standalone prompt-preset skills (character/scene/contact sheets, camera, lighting, covers)";
    }

    @Override
    public void migrate(Mongo mongo) {
        var now = Date.from(Instant.now());
        for (var name : SKILLS) {
            var content = resource("preset-skills/" + name + ".md");
            var update = new Document("$set", new Document()
                .append("namespace", "builtin")
                .append("name", name)
                .append("qualified_name", "builtin/" + name)
                .append("description", frontmatterDescription(content))
                .append("source_type", "upload")
                .append("content", content)
                .append("version", "1.0.0")
                .append("updated_at", now)
            ).append("$setOnInsert", new Document()
                .append("_id", skillId(name))
                .append("user_id", "system")
                .append("created_at", now));
            mongo.runCommand(new Document("update", "skills")
                .append("updates", List.of(new Document("q", new Document("_id", skillId(name))).append("u", update).append("upsert", Boolean.TRUE))));
        }
    }

    /** The listing description comes from the SKILL.md frontmatter so the two never drift. */
    private String frontmatterDescription(String content) {
        for (var line : content.split("\n")) {
            if (line.startsWith("description:")) return line.substring("description:".length()).trim();
        }
        return "";
    }

    private String resource(String path) {
        try (var stream = Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
            if (stream == null) throw new Error("missing classpath resource: " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read skill resource: " + path, e);
        }
    }
}
