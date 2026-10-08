package ai.core.server.skill;

import ai.core.server.domain.SkillDefinition;
import ai.core.server.domain.SkillResource;
import core.framework.inject.Inject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds a ZIP archive of a skill's SKILL.md and all resources, for materialization
 * into a sandbox runtime. The sandbox runtime unpacks this into /skill/{name}/.
 * Resource bytes stored in object storage are fetched on demand; the archive always
 * carries the original bytes.
 *
 * @author xander
 */
public class SkillArchiveBuilder {

    @Inject
    SkillBlobStore blobStore;

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
                    writeEntry(zip, r.path, resourceBytes(r));
                }
            }
            zip.finish();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("failed to build skill archive: " + def.qualifiedName, e);
        }
    }

    private byte[] resourceBytes(SkillResource resource) {
        if (resource.storagePath != null) return blobStore.fetch(resource.storagePath);
        return resource.content != null ? resource.content.getBytes(StandardCharsets.UTF_8) : new byte[0];
    }

    private void writeEntry(ZipOutputStream zip, String path, byte[] data) throws IOException {
        var entry = new ZipEntry(path);
        entry.setSize(data.length);
        zip.putNextEntry(entry);
        zip.write(data);
        zip.closeEntry();
    }
}
