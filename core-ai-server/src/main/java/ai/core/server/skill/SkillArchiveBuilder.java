package ai.core.server.skill;

import ai.core.server.domain.SkillDefinition;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds a ZIP archive of a skill's SKILL.md and all resources, for materialization
 * into a sandbox runtime. The sandbox runtime unpacks this into /skill/{name}/.
 *
 * @author xander
 */
public class SkillArchiveBuilder {

    public byte[] build(SkillDefinition def) {
        if (def.content == null) {
            throw new IllegalStateException("skill content is null: " + def.qualifiedName);
        }
        try (var baos = new ByteArrayOutputStream();
             var zip = new ZipOutputStream(baos)) {
            writeEntry(zip, "SKILL.md", def.content.getBytes(StandardCharsets.UTF_8));
            if (def.resources != null) {
                for (var r : def.resources) {
                    if (r.path == null) continue;
                    var bytes = r.content != null ? r.content.getBytes(StandardCharsets.UTF_8) : new byte[0];
                    writeEntry(zip, r.path, bytes);
                }
            }
            zip.finish();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("failed to build skill archive: " + def.qualifiedName, e);
        }
    }

    private void writeEntry(ZipOutputStream zip, String path, byte[] data) throws IOException {
        var entry = new ZipEntry(path);
        entry.setSize(data.length);
        zip.putNextEntry(entry);
        zip.write(data);
        zip.closeEntry();
    }
}
