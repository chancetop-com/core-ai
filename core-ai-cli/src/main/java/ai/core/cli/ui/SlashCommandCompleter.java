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
        String buffer = line.line().stripLeading();
        if (!buffer.startsWith("/")) return;

        int wordIndex = line.wordIndex();
        if (wordIndex == 0) {
            completeCommand(line.word(), candidates);
        } else if (wordIndex == 1) {
            completeSubCommand(line.words().getFirst(), line.word(), candidates);
        }
    }

    private void completeCommand(String word, List<Candidate> candidates) {
        for (SlashCommand cmd : SlashCommandRegistry.all()) {
            if (cmd.name().startsWith(word)) {
                candidates.add(new Candidate(
                        cmd.name(),
                        cmd.name(),
                        null,
                        cmd.description(),
                        null,
                        null,
                        cmd.subCommands().isEmpty()
                ));
            }
        }
    }

    private void completeSubCommand(String commandName, String word, List<Candidate> candidates) {
        for (SlashCommand cmd : SlashCommandRegistry.all()) {
            if (!cmd.name().equals(commandName)) continue;
            for (String sub : cmd.subCommands()) {
                if (sub.startsWith(word)) {
                    candidates.add(new Candidate(sub, sub, null, null, null, null, true));
                }
            }
            break;
        }
    }
}
