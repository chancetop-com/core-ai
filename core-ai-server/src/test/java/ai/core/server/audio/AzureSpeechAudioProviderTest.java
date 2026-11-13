package ai.core.server.audio;

import ai.core.media.audio.AsrSpec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @author stephen
 */
class AzureSpeechAudioProviderTest {
    private final AzureSpeechAudioProvider provider = new AzureSpeechAudioProvider();

    @Test
    void parsesPhraseSpeakersAndWordTimings() {
        var json = """
                {
                  "durationMilliseconds": 2400,
                  "combinedPhrases": [{"text": "你好，欢迎。"}],
                  "phrases": [
                    {
                      "offsetMilliseconds": 100,
                      "durationMilliseconds": 900,
                      "text": "你好，",
                      "speaker": 1,
                      "words": [
                        {"text": "你好", "offsetMilliseconds": 100, "durationMilliseconds": 800}
                      ]
                    },
                    {
                      "offsetMilliseconds": 1100,
                      "durationMilliseconds": 1000,
                      "text": "欢迎。",
                      "speaker": 2,
                      "words": [
                        {"text": "欢迎", "offsetMilliseconds": 1100, "durationMilliseconds": 900}
                      ]
                    }
                  ]
                }
                """;

        var result = provider.parseTranscription(json);

        assertEquals("你好，欢迎。", result.text());
        assertEquals(2, result.phrases().size());
        assertEquals("1", result.phrases().getFirst().speaker());
        assertEquals(0.1, result.phrases().getFirst().startSec());
        assertEquals(1.0, result.phrases().getFirst().endSec());
        assertEquals("2", result.phrases().getLast().speaker());
        assertEquals(2, result.words().size());
        assertEquals(1.1, result.words().getLast().startSec());
    }

    @Test
    void definitionEnablesDiarization() {
        var definition = provider.definition(new AsrSpec(new byte[]{1}, "audio/wav", "zh-CN", 4));

        assertEquals("{\"locales\":[\"zh-CN\"],\"diarization\":{\"enabled\":true,\"maxSpeakers\":4}}", definition);
    }
}
