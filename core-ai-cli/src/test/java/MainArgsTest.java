import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code --resume} takes an optional session id: a value resumes that exact session, no value keeps the
 * interactive picker.
 *
 * @author stephen
 */
class MainArgsTest {
    @Test
    void resumesAnExplicitSessionId() {
        var main = new Main();

        new CommandLine(main).parseArgs("--resume=cli-20261030-164801");

        assertEquals("cli-20261030-164801", main.resumeSessionId);
    }

    @Test
    void keepsThePickerWhenResumeHasNoValue() {
        var bare = new Main();
        new CommandLine(bare).parseArgs("--resume");
        assertEquals("", bare.resumeSessionId);

        var spaced = new Main();
        new CommandLine(spaced).parseArgs("--resume", "cli-20261030-164801");
        assertEquals("cli-20261030-164801", spaced.resumeSessionId);
    }

    @Test
    void rejectsContinueTogetherWithResume() {
        var main = new Main();
        new CommandLine(main).parseArgs("-c", "--resume=cli-20261030-164801");

        var error = assertThrows(CommandLine.ParameterException.class, main::checkSessionOptions);
        assertTrue(error.getMessage().contains("--continue"), error.getMessage());
        assertTrue(error.getMessage().contains("--resume"), error.getMessage());
    }
}
