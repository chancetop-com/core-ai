package ai.core.cli.speech;

import ai.core.cli.ConsoleWriter;
import picocli.CommandLine.Command;

import java.util.concurrent.Callable;

/**
 * @author stephen
 */
@Command(name = "speech", description = "Transcribe or synthesize speech with the server's speech models",
        subcommands = {SpeechTranscribeCommand.class, SpeechSynthesizeCommand.class, SpeechModelsCommand.class})
public class SpeechCommand implements Callable<Integer> {
    @Override
    public Integer call() {
        ConsoleWriter.println("Run 'core-ai-cli speech <subcommand> --help' for details.");
        return 0;
    }
}
