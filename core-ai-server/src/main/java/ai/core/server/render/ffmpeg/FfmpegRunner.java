package ai.core.server.render.ffmpeg;

import ai.core.server.domain.FileRecord;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Executes a declarative ffmpeg plan — pull inputs, write generated files, run the steps, persist the
 * declared outputs — somewhere. {@link SandboxFfmpegRunner} is the deployment path (a job-scoped sandbox
 * from the shared pool, design §9.4); {@link LocalFfmpegRunner} runs the binary on the server host for
 * local development. The plan is the contract, so callers never know which one they got.
 *
 * @author stephen
 */
public interface FfmpegRunner {
    Pattern VERSION_LINE = Pattern.compile("ffmpeg version (?:n)?(\\d+)[.\\-]");

    /** Major version out of the first line of {@code ffmpeg -version} ("ffmpeg version 8.0.1-full_build..."), 0 when unreadable. */
    static int parseFfmpegMajor(String versionOutput) {
        if (versionOutput == null) return 0;
        var matcher = VERSION_LINE.matcher(versionOutput);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    /** Plan file names are server-generated, but they end up in a shell command or on disk — verify anyway. */
    static String safeName(String fileName) {
        if (fileName == null || fileName.isBlank() || fileName.contains("..") || fileName.indexOf('/') >= 0 || fileName.indexOf('\\') >= 0)
            throw new IllegalArgumentException("unsafe plan file name: " + fileName);
        return fileName;
    }

    /** A step whose first token is {@code ffprobe} runs ffprobe (metadata into a file) instead of ffmpeg. */
    String PROBE = "ffprobe";

    static String binaryFor(List<String> step) {
        return !step.isEmpty() && PROBE.equals(step.getFirst()) ? PROBE : "ffmpeg";
    }

    static List<String> arguments(List<String> step) {
        return !step.isEmpty() && PROBE.equals(step.getFirst()) ? step.subList(1, step.size()) : step;
    }

    /** Products keyed by the plan's output kind: stored files, or the text of inline outputs. Throws on any failed step or missing output. */
    Map<String, Product> run(Plan plan);

    /** Called on the lease heartbeat while a plan runs, for runners whose execution site has its own TTL. */
    default void renew(String jobKey) {
    }

    record Download(String fileName, String url) {
    }

    record Write(String fileName, String content) {
    }

    /** @param inline small text products (ffprobe JSON) are returned as text instead of being filed */
    record Output(String fileName, String kind, String contentType, boolean inline) {
        public Output(String fileName, String kind, String contentType) {
            this(fileName, kind, contentType, false);
        }
    }

    /** A filed product ({@code file}) or an inline one ({@code text}); never both. */
    record Product(FileRecord file, String text) {
        public static Product of(FileRecord file) {
            return new Product(file, null);
        }
    }

    /**
     * @param jobKey             names the execution site (sandbox / temp dir); must be unique per in-flight plan
     * @param expectedFfmpegMajor the major the caller's cache keys are pinned to
     */
    record Plan(String jobKey, String userId, List<Download> downloads, List<Write> writes,
                List<List<String>> steps, List<Output> outputs,
                long stepTimeoutMs, int sandboxTtlSeconds, int expectedFfmpegMajor) {
        public Plan {
            downloads = downloads == null ? List.of() : downloads;
            writes = writes == null ? List.of() : writes;
            steps = steps == null ? List.of() : steps;
            outputs = outputs == null ? List.of() : outputs;
        }
    }
}
