package ai.core.server.domain;

import core.framework.mongo.Field;

/**
 * One input asset a media generation ran with: a reference image/video, the inpaint mask, or the video an
 * edit continued from. The result alone cannot explain a generation, so the recipe is kept next to it —
 * which reference the prompt addressed, what it contributed, and where the bytes can still be seen.
 * <p>
 * {@code kind} says how the asset is reached: {@code media} points at an earlier generation of this
 * platform (job_id, and file_id once that generation stored its bytes), {@code url} at an external
 * source, {@code inline} at content that was only ever sent as base64. Both of the latter are persisted
 * as file records, so the record survives an expiring upstream URL.
 *
 * @author stephen
 */
public class MediaJobInput {
    public static final String KIND_MEDIA = "media";
    public static final String KIND_URL = "url";
    public static final String KIND_INLINE = "inline";

    /** The inpaint mask travels as an ordinary reference; the role is what separates it in the record. */
    public static final String ROLE_MASK = "mask";
    /** The video a conversational video edit continued from ({@code previous_video_id}). */
    public static final String ROLE_PREVIOUS_VIDEO = "previous_video";

    @Field(name = "kind")
    public String kind;

    /** Author-time handle the prompt addressed ({@code @char_lin}), absent when the caller named none. */
    @Field(name = "name")
    public String name;

    /** {@code first_frame} / {@code last_frame} / {@code subject} / … — drives reference trimming priority. */
    @Field(name = "role")
    public String role;

    @Field(name = "modality")
    public String modality;

    /** Producing media job when this input is an earlier generation of this platform. */
    @Field(name = "job_id")
    public String jobId;

    @Field(name = "file_id")
    public String fileId;

    @Field(name = "content_type")
    public String contentType;

    /** External source, kept even when the bytes were persisted — it is what the caller passed. */
    @Field(name = "url")
    public String url;
}
