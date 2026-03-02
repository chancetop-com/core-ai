package ai.core.cli.ui;

import ai.core.cli.command.SlashCommand;
import ai.core.cli.command.SlashCommandRegistry;
import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;

import java.util.List;

/**
 * @author xander
 */
public class SlashCommandCompleter implements Completer {

    @Override
    public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
        String word = line.word();
        if (word == null || !word.startsWith("/")) {
            return;
        }
        for (SlashCommand cmd : SlashCommandRegistry.all()) {
            if (cmd.name().startsWith(word)) {
                candidates.add(new Candidate(
                        cmd.name(),
                        cmd.name(),
                        null,
                        cmd.description(),
                        null,
                        null,
                        true
                ));
            }
        }
    }
}
