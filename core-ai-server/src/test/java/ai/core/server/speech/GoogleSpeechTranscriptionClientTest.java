package ai.core.server.speech;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @author stephen
 */
class GoogleSpeechTranscriptionClientTest {
    private final GoogleSpeechTranscriptionClient client = new GoogleSpeechTranscriptionClient(
            "https://speech.googleapis.com", "project-1", "global", "api-key", null, 8);

    @Test
    void parsesGoogleV2ResultsWithSpeakerAndOffsets() {
        var json = """
                {
                  "results": [
                    {
                      "languageCode": "zh-CN",
                      "alternatives": [{
                        "transcript": "你好，欢迎。",
                        "words": [
                          {"word": "你好", "startOffset": "0.100s", "endOffset": "0.600s", "speakerLabel": "1"},
                          {"word": "欢迎", "startOffset": "0.700s", "endOffset": "1.200s", "speakerLabel": "2"}
                        ]
                      }]
                    }
                  ]
                }
                """;

        var result = client.parse(json, "zh");

        assertEquals("你好，欢迎。", result.text());
        assertEquals("zh-CN", result.language());
        assertEquals(1.2, result.durationSeconds(), 0.0001);
        assertEquals(1, result.segments().size());
        assertEquals("1", result.segments().getFirst().speaker());
        assertEquals(0.1, result.segments().getFirst().startSec(), 0.0001);
        assertEquals(1.2, result.segments().getFirst().endSec(), 0.0001);
    }

    @Test
    void joinsEnglishResultsWithSpaces() {
        var json = """
                {
                  "results": [
                    {"languageCode": "en-US", "alternatives": [{"transcript": "hello"}]},
                    {"languageCode": "en-US", "alternatives": [{"transcript": "world"}]}
                  ]
                }
                """;

        var result = client.parse(json, "en-US");

        assertEquals("hello world", result.text());
    }
}
