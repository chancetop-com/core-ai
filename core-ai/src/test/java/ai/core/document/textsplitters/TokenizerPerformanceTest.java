package ai.core.document.textsplitters;

import ai.core.document.Tokenizer;
import com.knuddels.jtokkit.api.EncodingType;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Performance test for Tokenizer token counting.
 * @author xander
 */
class TokenizerPerformanceTest {
    private final Logger logger = LoggerFactory.getLogger(TokenizerPerformanceTest.class);

    @Test
    void testTokenCountPerformance() {
        // warm up - first call initializes the encoding registry
        Tokenizer.tokenCount("warmup text");

        // test with different text lengths
        String shortText = "Hello, world!";
        String mediumText = generateText(1000);
        String longText = generateText(10000);
        String veryLongText = generateText(100000);

        logger.info("=== Token Count Performance Test ===");

        // short text
        measureTokenCount("Short text (13 chars)", shortText, 10000);

        // medium text
        measureTokenCount("Medium text (1K chars)", mediumText, 1000);

        // long text
        measureTokenCount("Long text (10K chars)", longText, 100);

        // very long text
        measureTokenCount("Very long text (100K chars)", veryLongText, 10);
    }

    @Test
    void testEncodingTypeComparison() {
        // warm up
        Tokenizer.tokenCount("warmup", EncodingType.CL100K_BASE);
        Tokenizer.tokenCount("warmup", EncodingType.P50K_BASE);
        Tokenizer.tokenCount("warmup", EncodingType.R50K_BASE);

        String text = generateText(10000);
        int iterations = 100;

        logger.info("=== Encoding Type Comparison (10K chars, 100 iterations) ===");

        measureTokenCountWithEncoding("CL100K_BASE (GPT-4/3.5)", text, iterations, EncodingType.CL100K_BASE);
        measureTokenCountWithEncoding("P50K_BASE (Codex)", text, iterations, EncodingType.P50K_BASE);
        measureTokenCountWithEncoding("R50K_BASE (GPT-2)", text, iterations, EncodingType.R50K_BASE);
    }

    @Test
    void testEncodeDecodePerformance() {
        // warm up
        Tokenizer.encode("warmup");
        Tokenizer.decode(Tokenizer.encode("warmup"));

        String text = generateText(10000);
        int iterations = 100;

        logger.info("=== Encode/Decode Performance (10K chars, 100 iterations) ===");

        // encode performance
        long startEncode = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            Tokenizer.encode(text);
        }
        long encodeTime = System.nanoTime() - startEncode;

        // decode performance
        var encoded = Tokenizer.encode(text);
        long startDecode = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            Tokenizer.decode(encoded);
        }
        long decodeTime = System.nanoTime() - startDecode;

        logger.info("Encode: Total={}ms, Avg={}ms, Token count={}",
                encodeTime / 1_000_000,
                String.format("%.3f", encodeTime / 1_000_000.0 / iterations),
                encoded.size());
        logger.info("Decode: Total={}ms, Avg={}ms",
                decodeTime / 1_000_000,
                String.format("%.3f", decodeTime / 1_000_000.0 / iterations));
    }

    @Test
    void testConcurrentTokenCount() throws InterruptedException {
        // warm up
        Tokenizer.tokenCount("warmup");

        String text = generateText(5000);
        int threadCount = 10;
        int iterationsPerThread = 100;

        logger.info("=== Concurrent Token Count Test ===");
        logger.info("Threads: {}, Iterations per thread: {}", threadCount, iterationsPerThread);

        Thread[] threads = new Thread[threadCount];
        long[] threadTimes = new long[threadCount];

        long startTotal = System.nanoTime();

        for (int t = 0; t < threadCount; t++) {
            final int threadIndex = t;
            threads[t] = new Thread(() -> {
                long start = System.nanoTime();
                for (int i = 0; i < iterationsPerThread; i++) {
                    Tokenizer.tokenCount(text);
                }
                threadTimes[threadIndex] = System.nanoTime() - start;
            });
            threads[t].start();
        }

        for (Thread thread : threads) {
            thread.join();
        }

        long totalTime = System.nanoTime() - startTotal;

        long sumThreadTimes = 0;
        for (long time : threadTimes) {
            sumThreadTimes += time;
        }

        logger.info("Total wall time: {}ms", totalTime / 1_000_000);
        logger.info("Sum of thread times: {}ms", sumThreadTimes / 1_000_000);
        logger.info("Avg per thread: {}ms", String.format("%.3f", sumThreadTimes / 1_000_000.0 / threadCount));
        logger.info("Avg per call: {}ms",
                String.format("%.3f", totalTime / 1_000_000.0 / threadCount / iterationsPerThread));
        logger.info("Throughput: {} calls/sec",
                String.format("%.0f", threadCount * iterationsPerThread / (totalTime / 1_000_000_000.0)));
    }

    private void measureTokenCount(String description, String text, int iterations) {
        long start = System.nanoTime();
        int tokenCount = 0;
        for (int i = 0; i < iterations; i++) {
            tokenCount = Tokenizer.tokenCount(text);
        }
        long elapsed = System.nanoTime() - start;

        logger.info("{}:", description);
        logger.info("  Token count: {}", tokenCount);
        logger.info("  Iterations: {}", iterations);
        logger.info("  Total time: {}ms", elapsed / 1_000_000);
        logger.info("  Avg time: {}ms", String.format("%.3f", elapsed / 1_000_000.0 / iterations));
    }

    private void measureTokenCountWithEncoding(String description, String text, int iterations, EncodingType type) {
        long start = System.nanoTime();
        int tokenCount = 0;
        for (int i = 0; i < iterations; i++) {
            tokenCount = Tokenizer.tokenCount(text, type);
        }
        long elapsed = System.nanoTime() - start;

        logger.info("{}: tokens={}, total={}ms, avg={}ms",
                description, tokenCount, elapsed / 1_000_000, String.format("%.3f", elapsed / 1_000_000.0 / iterations));
    }

    private String generateText(int length) {
        StringBuilder sb = new StringBuilder(length);
        String sample = "The quick brown fox jumps over the lazy dog. ";
        while (sb.length() < length) {
            sb.append(sample);
        }
        return sb.substring(0, length);
    }
}