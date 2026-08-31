package ai.core.server.render;

/**
 * Persistence seam for render products: content-hash dedup upload plus fileId to URL/hash resolution.
 * Kept as an interface so callers stay unit-testable without object storage.
 *
 * @author stephen
 */
public interface RenderProductStore {
    StoredProduct storeBytes(String userId, String fileName, String contentType, byte[] bytes);

    StoredProduct storeFromUrl(String userId, String fileName, String url);

    /** Resolves an existing file to a downloadable URL and its content hash; null when absent. */
    StoredProduct resolve(String fileId);

    /** Resolves by content hash (the RenderGraph address space); null when absent. */
    StoredProduct resolveByHash(String userId, String contentHash);

    record StoredProduct(String fileId, String contentHash, String url) {
    }
}
