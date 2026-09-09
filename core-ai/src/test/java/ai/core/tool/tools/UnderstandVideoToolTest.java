package ai.core.tool.tools;

import ai.core.agent.AttachedContent;
import ai.core.agent.ExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnderstandVideoToolTest {
    private static final String FULL_ID = "video_7bb80432-cf17-4742-933b-5049d55ce644";
    private static final String OTHER_ID = "video_756b781b-1b52-4a0e-85af-13210ecbc427";

    private String askedReferenceId;
    private UnderstandVideoTool tool;

    @BeforeEach
    void setUp() {
        askedReferenceId = null;
        tool = UnderstandVideoTool.builder((owner, referenceId, model, question) -> {
            askedReferenceId = referenceId;
            return new UnderstandVideoTool.VideoUnderstandingResult("answer", model, "hit");
        }).build();
    }

    @Test
    void abbreviatedIdResolvesToTheAttachedVideo() {
        var result = tool.execute(arguments("video_7bb80432"), contextWith(FULL_ID, OTHER_ID));

        assertTrue(result.isCompleted(), result.getResult());
        assertEquals(FULL_ID, askedReferenceId);
    }

    @Test
    void usageIsAttributedToTheModelThatAnswered() {
        tool = UnderstandVideoTool.builder((owner, referenceId, model, question) ->
                new UnderstandVideoTool.VideoUnderstandingResult("answer", "gemini-video", "miss", 10, 5, 15)).build();
        var context = contextWith(FULL_ID);
        context.setMultiModalModel("vision-model");

        var result = tool.execute(arguments(FULL_ID), context);

        assertTrue(result.isCompleted(), result.getResult());
        assertEquals("gemini-video", result.getLlmModel());
    }

    @Test
    void exactIdIsPassedThrough() {
        var result = tool.execute(arguments(FULL_ID), contextWith(FULL_ID, OTHER_ID));

        assertTrue(result.isCompleted(), result.getResult());
        assertEquals(FULL_ID, askedReferenceId);
    }

    @Test
    void ambiguousOrUnknownIdFailsAndListsAttachedVideos() {
        var result = tool.execute(arguments("video_7bb80432"),
                contextWith(FULL_ID, "video_7bb80432-0000-4742-933b-5049d55ce644"));

        assertTrue(result.isFailed());
        assertTrue(result.getResult().contains(FULL_ID), result.getResult());
        assertNull(askedReferenceId);
    }

    @Test
    void shortPrefixNeverMatches() {
        var result = tool.execute(arguments("video_7bb"), contextWith(FULL_ID));

        assertTrue(result.isFailed());
        assertNull(askedReferenceId);
    }

    @Test
    void withoutAttachmentsTheServiceValidatesOwnership() {
        var result = tool.execute(arguments("video_7bb80432"), ExecutionContext.builder().sessionId("s").userId("u").build());

        assertTrue(result.isCompleted(), result.getResult());
        assertEquals("video_7bb80432", askedReferenceId);
    }

    private String arguments(String referenceId) {
        return "{\"attachment_reference_id\":\"" + referenceId + "\",\"question\":\"what happens?\"}";
    }

    private ExecutionContext contextWith(String... referenceIds) {
        var context = ExecutionContext.builder().sessionId("s").userId("u").build();
        context.setAttachedContents(List.of(referenceIds).stream()
                .map(id -> AttachedContent.ofReference(id, "video/mp4", id + ".mp4")).toList());
        return context;
    }
}
