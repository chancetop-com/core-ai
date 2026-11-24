package ai.core.media;

import core.framework.api.json.Property;

/**
 * A lightweight reference to one media object. It carries metadata only: never bytes, never a
 * secret, never a signed URL. The bytes are owned by a {@link SessionMediaStore}.
 *
 * @author core-ai
 */
public class MediaRef {
    public static MediaRef blob(String id, String container, String blobName, String mediaType, String format,
                                Long sizeBytes) {
        var ref = new MediaRef();
        ref.id = id;
        ref.kind = MediaRefKind.BLOB;
        ref.container = container;
        ref.blobName = blobName;
        ref.mediaType = mediaType;
        ref.format = format;
        ref.sizeBytes = sizeBytes;
        return ref;
    }

    public static MediaRef file(String id, String path, String mediaType, String format, long sizeBytes, String sha256) {
        var ref = new MediaRef();
        ref.id = id;
        ref.kind = MediaRefKind.FILE;
        ref.path = path;
        ref.mediaType = mediaType;
        ref.format = format;
        ref.sizeBytes = sizeBytes;
        ref.sha256 = sha256;
        return ref;
    }

    public static MediaRef sandbox(String id, String path, String mediaType, Long sizeBytes) {
        var ref = new MediaRef();
        ref.id = id;
        ref.kind = MediaRefKind.SANDBOX;
        ref.path = path;
        ref.mediaType = mediaType;
        ref.sizeBytes = sizeBytes;
        return ref;
    }

    public static MediaRef url(String id, String url, String mediaType, Long sizeBytes) {
        var ref = new MediaRef();
        ref.id = id;
        ref.kind = MediaRefKind.URL;
        ref.url = url;
        ref.mediaType = mediaType;
        ref.sizeBytes = sizeBytes;
        return ref;
    }

    private static void require(Object value, String field) {
        if (value == null) throw new IllegalArgumentException("media ref " + field + " is required");
    }

    private static void rejectDataUri(String value) {
        if (value.startsWith("data:")) throw new IllegalArgumentException("media ref must not carry inline bytes");
    }

    private static void rejectTraversal(String value) {
        if (value.contains("..")) throw new IllegalArgumentException("media ref path must not traverse");
        rejectDataUri(value);
    }

    /** Opaque identifier; the server uses {@code media_<uuid>}, the CLI uses the content sha256. */    @Property(name = "id")
    public String id;

    @Property(name = "kind")
    public MediaRefKind kind;

    @Property(name = "container")
    public String container;

    @Property(name = "blob_name")
    public String blobName;

    @Property(name = "path")
    public String path;

    @Property(name = "url")
    public String url;

    @Property(name = "media_type")
    public String mediaType;

    @Property(name = "format")
    public String format;

    @Property(name = "size_bytes")
    public Long sizeBytes;

    @Property(name = "sha256")
    public String sha256;

    @Property(name = "etag")
    public String etag;

    @Property(name = "display_name")
    public String displayName;

    @Property(name = "source")
    public String source;

    public MediaRef sha256(String sha256) {
        this.sha256 = sha256;
        return this;
    }

    public MediaRef etag(String etag) {
        this.etag = etag;
        return this;
    }

    public MediaRef displayName(String displayName) {
        this.displayName = displayName;
        return this;
    }

    public MediaRef source(String source) {
        this.source = source;
        return this;
    }

    /** A label safe to show to the model and the trace viewer: no locator, no bytes. */
    public String label() {
        if (displayName != null && !displayName.isBlank()) return displayName;
        if (source != null && !source.isBlank()) return source;
        return mediaType != null ? mediaType : "image";
    }

    /**
     * Rejects a reference that cannot be resolved or that could escape its store root. Stores still
     * perform the authoritative root/ownership check; this only catches malformed refs early.
     */
    public MediaRef validate() {
        if (kind == null) throw new IllegalArgumentException("media ref kind is required");
        switch (kind) {
            case BLOB -> {
                require(container, "container");
                require(blobName, "blobName");
                require(sizeBytes, "sizeBytes");
                rejectDataUri(blobName);
            }
            case FILE, SANDBOX -> {
                require(path, "path");
                require(sizeBytes, "sizeBytes");
                rejectTraversal(path);
            }
            case URL -> {
                require(url, "url");
                if (!url.startsWith("https://") && !url.startsWith("http://")) {
                    throw new IllegalArgumentException("media ref url must be http(s)");
                }
            }
            default -> throw new IllegalArgumentException("unsupported media ref kind: " + kind);
        }
        return this;
    }
}
