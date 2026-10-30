package ai.core.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Kills a writer mid-save and asserts the session file is never left torn or empty. Spawns child JVMs and
 * writes tens of megabytes per round, so it only runs when {@code CORE_AI_PROBE=true} is set:
 * {@code CORE_AI_PROBE=true ./gradlew :core-ai:test --tests ai.core.session.TornWriteProbeTest}.
 *
 * @author stephen
 */
class TornWriteProbeTest {
    @Test
    @EnabledIfEnvironmentVariable(named = "CORE_AI_PROBE", matches = "true")
    void killedWriterNeverLeavesATornOrEmptySnapshot() throws Exception {
        var result = TornWriteProbe.run(Files.createTempDirectory("torn-write-probe-test"), 12, System.out::println);
        System.out.println(result);
        assertEquals(0, result.torn + result.empty, result.toString());
    }
}
