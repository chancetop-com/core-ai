package ai.core.api.server.media;

import core.framework.api.json.Property;

/**
 * One input asset a generation ran with. Rendered next to the produced artifact, because a result cannot be
 * judged — or the experiment repeated — without the references that were sent with it.
 *
 * @author stephen
 */
public class MediaJobInputView {
    /** {@code media} (an earlier generation of this platform), {@code url}, or {@code inline}. */
    @Property(name = "kind")
    public String kind;

    @Property(name = "name")
    public String name;

    @Property(name = "role")
    public String role;

    @Property(name = "modality")
    public String modality;

    @Property(name = "jobId")
    public String jobId;

    @Property(name = "fileId")
    public String fileId;

    @Property(name = "contentType")
    public String contentType;

    @Property(name = "url")
    public String url;
}
