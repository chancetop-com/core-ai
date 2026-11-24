package ai.core.media;

import core.framework.api.json.Property;

/**
 * Where the bytes of a {@link MediaRef} live. The kind decides which fields are required and which
 * store implementation can resolve the reference.
 *
 * @author core-ai
 */
public enum MediaRefKind {
    /** A durable object-storage object owned by the server media store. */
    @Property(name = "blob")
    BLOB,

    /** A file inside the session media directory (CLI / Desktop). */
    @Property(name = "file")
    FILE,

    /** A file inside a sandbox workspace; it can disappear when the sandbox is released. */
    @Property(name = "sandbox")
    SANDBOX,

    /** An external https URL; not guaranteed to stay reachable. */
    @Property(name = "url")
    URL
}
