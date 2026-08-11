package ai.core.agent;

/**
 * @author stephen
 */
public class AttachedContent {
    public static AttachedContent ofUrl(String url, AttachedContentType type) {
        var content = new AttachedContent();
        content.url = url;
        content.type = type;
        return content;
    }

    public static AttachedContent ofBase64(String data, String mediaType, AttachedContentType type) {
        return ofBase64(data, mediaType, type, null);
    }

    public static AttachedContent ofBase64(String data, String mediaType, AttachedContentType type, String filename) {
        var content = new AttachedContent();
        content.data = data;
        content.mediaType = mediaType;
        content.type = type;
        content.filename = filename;
        return content;
    }

    public static AttachedContent ofReference(String referenceId, String mediaType, String filename) {
        var content = new AttachedContent();
        content.url = referenceId;
        content.mediaType = mediaType;
        content.filename = filename;
        content.type = AttachedContentType.VIDEO;
        return content;
    }

    public String url;
    public String data;
    public String mediaType;
    public String filename;
    public AttachedContentType type;

    public boolean isBase64() {
        return data != null;
    }

    public enum AttachedContentType {
        IMAGE,
        PDF,
        VIDEO
    }
}
