package ai.core.tool.tools;

import ai.core.agent.ExecutionContext;
import ai.core.api.server.speech.SpeechTranscriptSegmentView;
import ai.core.api.server.speech.SpeechTranscriptionView;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class TranscribeAudioToolTest {
    @Test
    void returnsTranscriptText() throws Exception {
        var file = Files.createTempFile("transcribe-tool-", ".wav");
        Files.write(file, new byte[]{1});
        var tool = TranscribeAudioTool.builder((path, language, model, prompt) -> view("hello")).build();

        var result = tool.execute("{\"file\":\"" + escape(file) + "\"}", ExecutionContext.empty());

        assertFalse(result.isFailed());
        assertEquals("hello", result.getResult());
    }

    @Test
    void rendersTimestampsWhenRequested() throws Exception {
        var file = Files.createTempFile("transcribe-tool-", ".wav");
        Files.write(file, new byte[]{1});
        var view = view("hello world");
        var first = new SpeechTranscriptSegmentView();
        first.text = "hello";
        first.start = 1.0;
        first.end = 1.5;
        var second = new SpeechTranscriptSegmentView();
        second.text = "world";
        second.start = 61.0;
        second.end = 62.0;
        view.segments = List.of(first, second);
        var tool = TranscribeAudioTool.builder((path, language, model, prompt) -> view).build();

        var result = tool.execute("{\"file\":\"" + escape(file) + "\",\"timestamps\":true}", ExecutionContext.empty());

        assertTrue(result.getResult().contains("[00:01] hello"));
        assertTrue(result.getResult().contains("[01:01] world"));
    }

    @Test
    void reportsMissingFiles() {
        var tool = TranscribeAudioTool.builder((path, language, model, prompt) -> view("never")).build();

        var result = tool.execute("{\"file\":\"definitely-not-here.wav\"}", ExecutionContext.empty());

        assertTrue(result.isFailed());
        assertTrue(result.getResult().contains("not found"));
    }

    private SpeechTranscriptionView view(String text) {
        var view = new SpeechTranscriptionView();
        view.text = text;
        view.model = "whisper-1";
        view.provider = "azure-openai";
        view.language = "en";
        view.segments = List.of();
        return view;
    }

    private String escape(Path path) {
        return path.toString().replace("\\", "\\\\");
    }
}
