package ai.core.session;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.function.Consumer;

/**
 * Manual crash probe for {@link FileSessionPersistence#save}: the parent writes a valid snapshot, spawns a
 * child JVM that overwrites it through the real persistence class with a much larger snapshot, and kills
 * the child once the file has started changing. The on-disk outcome of each round is classified as the
 * old snapshot intact, the new snapshot complete, an empty file, or a torn file. A crash-safe writer must
 * never produce the last two. Driven by {@link TornWriteProbeTest}; also runnable directly with the test
 * runtime classpath: {@code java -cp <test classpath> ai.core.session.TornWriteProbe [rounds]}.
 *
 * @author stephen
 */
public final class TornWriteProbe {
    static final String SESSION_ID = "probe";
    private static final int NEW_PAYLOAD_CHARS = 60_000_000;
    private static final int OLD_PAYLOAD_CHARS = 200_000;

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "child".equals(args[0])) {
            new FileSessionPersistence(args[1]).save(SESSION_ID, payload("N", NEW_PAYLOAD_CHARS));
            return;
        }
        int rounds = args.length > 0 ? Integer.parseInt(args[0]) : 12;
        var result = run(Files.createTempDirectory("torn-write-probe"), rounds, System.out::println);
        System.out.println(result);
    }

    static Result run(Path dir, int rounds, Consumer<String> log) throws IOException, InterruptedException {
        var persistence = new FileSessionPersistence(dir.toString());
        var file = dir.resolve(SESSION_ID + ".data");
        var original = payload("O", OLD_PAYLOAD_CHARS);
        long newLength = payload("N", NEW_PAYLOAD_CHARS).length();
        var random = new Random(7);
        var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var result = new Result();
        for (int round = 1; round <= rounds; round++) {
            persistence.save(SESSION_ID, original);
            long before = Files.size(file);
            var child = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                    TornWriteProbe.class.getName(), "child", dir.toString()).inheritIO().start();
            long deadline = System.currentTimeMillis() + 20_000;
            while (snapshotUnchanged(dir, file, before) && child.isAlive() && System.currentTimeMillis() < deadline) {
                Thread.onSpinWait();
            }
            Thread.sleep(random.nextInt(40));
            child.destroyForcibly();
            child.waitFor();
            long size = Files.exists(file) ? Files.size(file) : -1;
            String state;
            if (size == original.length()) {
                result.intact++;
                state = "OLD SNAPSHOT INTACT";
            } else if (size == newLength) {
                result.complete++;
                state = "NEW SNAPSHOT COMPLETE";
            } else if (size <= 0) {
                result.empty++;
                state = "EMPTY OR MISSING";
            } else {
                result.torn++;
                state = "PARTIAL / TORN";
            }
            log.accept(String.format("round %2d: size=%,d -> %s", round, size, state));
        }
        core.framework.util.Files.deleteDir(dir);
        return result;
    }

    private static boolean snapshotUnchanged(Path dir, Path file, long before) throws IOException {
        if (!Files.exists(file) || Files.size(file) != before) return false;
        try (var entries = Files.list(dir)) {
            return entries.noneMatch(path -> !path.equals(file));
        }
    }

    private static String payload(String fill, int chars) {
        return "{\"messages\":[\"" + fill.repeat(chars) + "\"],\"status\":\"COMPLETED\"}";
    }

    private TornWriteProbe() {
    }

    static final class Result {
        int intact;
        int complete;
        int empty;
        int torn;

        @Override
        public String toString() {
            return "summary: intact=" + intact + " complete=" + complete + " empty=" + empty + " torn=" + torn;
        }
    }
}
