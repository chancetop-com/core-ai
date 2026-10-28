package ai.core.cli.appserver;

import ai.core.agent.AttachedContent;
import ai.core.utils.ImageDownscaler;
import ai.core.utils.JsonUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class SendPartsTest {
    @TempDir
    Path workspace;

    @TempDir
    Path staging;

    @Test
    void textOnlyPart() {
        var parsed = SendParts.parse(parts("{\"type\":\"text\",\"text\":\"hello\"}"), workspace, staging);
        assertEquals("hello", parsed.text());
        assertTrue(parsed.attachments().isEmpty());
    }

    @Test
    void filePartIsInlined() throws Exception {
        Files.writeString(workspace.resolve("notes.md"), "# notes");
        var parsed = SendParts.parse(parts("{\"type\":\"file\",\"path\":\"notes.md\"}"), workspace, staging);
        assertTrue(parsed.text().contains("```md"));
        assertTrue(parsed.text().contains("# notes"));
    }

    @Test
    void imagePartBecomesAttachment() throws Exception {
        Files.write(workspace.resolve("img.png"), new byte[]{1, 2, 3});
        var parsed = SendParts.parse(parts("{\"type\":\"image\",\"path\":\"img.png\"}"), workspace, staging);
        assertEquals(1, parsed.attachments().size());
        var attachment = parsed.attachments().getFirst();
        assertEquals(AttachedContent.AttachedContentType.IMAGE, attachment.type);
        assertEquals("image/png", attachment.mediaType);
        assertEquals("img.png", attachment.filename);
        assertTrue(attachment.isBase64());
    }

    @Test
    void imagePartIsShrunkBeforeItBecomesAnAttachment() throws Exception {
        Files.write(workspace.resolve("img.png"), new byte[4096]);
        ImageDownscaler.register(base64 -> Base64.getEncoder().encodeToString(new byte[]{1}));
        try {
            var parsed = SendParts.parse(parts("{\"type\":\"image\",\"path\":\"img.png\"}"), workspace, staging);
            var attachment = parsed.attachments().getFirst();
            assertEquals("AQ==", attachment.data);
            assertEquals("image/jpeg", attachment.mediaType);
        } finally {
            ImageDownscaler.register(null);
        }
    }

    @Test
    void oversizedImageIsRejectedBeforeItIsRead() throws Exception {
        try (var file = new RandomAccessFile(workspace.resolve("huge.png").toFile(), "rw")) {
            file.setLength(50L * 1024 * 1024 + 1);
        }

        var error = assertThrows(RpcException.class,
                () -> SendParts.parse(parts("{\"type\":\"image\",\"path\":\"huge.png\"}"), workspace, staging));

        assertEquals("PART_TOO_LARGE", error.data().path("code").asText());
    }

    @Test
    void imageOnlyPartHasContentWithoutText() throws Exception {
        Files.write(workspace.resolve("img.png"), new byte[]{1, 2, 3});
        var parsed = SendParts.parse(parts("{\"type\":\"image\",\"path\":\"img.png\"}"), workspace, staging);
        assertTrue(parsed.text().isBlank());
        assertEquals(1, parsed.attachments().size());
        assertTrue(parsed.hasContent());
    }

    @Test
    void blankTextWithoutAttachmentsHasNoContent() {
        var parsed = SendParts.parse(parts("{\"type\":\"text\",\"text\":\"  \"}"), workspace, staging);
        assertTrue(parsed.text().isBlank());
        assertTrue(parsed.attachments().isEmpty());
        assertFalse(parsed.hasContent());
    }

    @Test
    void stagingPathIsAllowed() throws Exception {
        var file = staging.resolve("draft.txt");
        Files.writeString(file, "draft");
        var json = "{\"type\":\"file\",\"path\":\"" + file.toString().replace("\\", "\\\\") + "\"}";
        var parsed = SendParts.parse(parts(json), workspace, staging);
        assertTrue(parsed.text().contains("draft"));
    }

    @Test
    void pathOutsideWorkspaceIsRejected() {
        var error = assertThrows(RpcException.class,
                () -> SendParts.parse(parts("{\"type\":\"file\",\"path\":\"../escape.txt\"}"), workspace, staging));
        assertEquals("PATH_NOT_ALLOWED", error.data().path("code").asText());
    }

    @Test
    void missingFileIsReported() {
        var error = assertThrows(RpcException.class,
                () -> SendParts.parse(parts("{\"type\":\"file\",\"path\":\"nope.txt\"}"), workspace, staging));
        assertEquals("PART_NOT_FOUND", error.data().path("code").asText());
    }

    @Test
    void emptyPartsIsInvalid() {
        var error = assertThrows(RpcException.class, () -> SendParts.parse(Params.object(), workspace, staging));
        assertEquals(RpcException.INVALID_PARAMS, error.code());
    }

    private ObjectNode parts(String... partJsons) {
        var params = Params.object();
        var array = JsonUtil.OBJECT_MAPPER.createArrayNode();
        for (var part : partJsons) {
            array.add(parsePart(part));
        }
        params.set("parts", array);
        return params;
    }

    private JsonNode parsePart(String json) {
        try {
            return JsonUtil.OBJECT_MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
