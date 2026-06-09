package ai.core.server.domain;

import core.framework.mongo.Field;

import java.util.ArrayList;
import java.util.List;

/**
 * A lean, downstream-facing reference to a file an executed node produced — the artifact's identity plus an
 * absolute download URL and metadata, but never the bytes. This is what flows through the workflow variable
 * pool ({@code nodes.<id>.artifacts}) and onto the run output, mapped from a child run's {@link AgentRunArtifact}.
 * Files travel by reference (the platform's stance: data, including files, flows through the pool, not sandbox FS);
 * the consumer fetches the bytes from {@code url} itself.
 *
 * @author Xander
 */
public class ArtifactRef {
    @Field(name = "file_id")
    public String fileId;

    @Field(name = "file_name")
    public String fileName;

    @Field(name = "content_type")
    public String contentType;

    @Field(name = "size")
    public Long size;

    @Field(name = "url")
    public String url;

    @Field(name = "title")
    public String title;

    @Field(name = "description")
    public String description;

    /** Map a submitted run artifact to a downstream reference; {@code url} is the caller-resolvable download URL. */
    public static ArtifactRef of(AgentRunArtifact artifact, String url) {
        var ref = new ArtifactRef();
        ref.fileId = artifact.fileId;
        ref.fileName = artifact.fileName;
        ref.contentType = artifact.contentType;
        ref.size = artifact.size;
        ref.url = url;
        ref.title = artifact.title;
        ref.description = artifact.description;
        return ref;
    }

    /** Merge several artifact lists, de-duplicating by {@code file_id} and preserving first-seen order. */
    public static List<ArtifactRef> union(List<List<ArtifactRef>> lists) {
        var seen = new java.util.LinkedHashSet<String>();
        var merged = new ArrayList<ArtifactRef>();
        for (List<ArtifactRef> list : lists) {
            if (list == null) continue;
            for (ArtifactRef ref : list) {
                if (ref.fileId == null || seen.add(ref.fileId)) merged.add(ref);
            }
        }
        return merged;
    }
}
