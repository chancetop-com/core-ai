package ai.core.media.reference;

/**
 * Fetches externally-referenced media bytes. Only explicitly-external references go through this,
 * which is what keeps the SSRF surface small: platform-owned references resolve through the gateway
 * without an outbound fetch.
 *
 * @author stephen
 */
public interface RemoteMediaLoader {
    Loaded load(String url);

    record Loaded(byte[] data, String contentType) {
        public String contentTypeOr(String fallback) {
            return contentType != null && !contentType.isBlank() ? contentType : fallback;
        }
    }
}
