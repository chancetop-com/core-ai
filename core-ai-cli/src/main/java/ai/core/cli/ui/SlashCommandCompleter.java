package ai.core.cli.ui;

import ai.core.cli.command.SlashCommand;
import ai.core.cli.command.SlashCommandRegistry;
import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;

import java.util.List;
import java.util.Locale;

/**
 * @author xander
 */
public class SlashCommandCompleter implements Completer {

    private static String subCommandOf(String name) {
        int idx = name.indexOf(' ');
        return idx > 0 ? name.substring(idx + 1) : name;
    }

    private static String parentOf(String name) {
        int idx = name.indexOf(' ');
        return idx > 0 ? name.substring(0, idx) : name;
    }

    private volatile List<SlashCommand> commands = SlashCommandRegistry.all();

    public void setCommands(List<SlashCommand> commands) {
        this.commands = commands;
    }

    public void resetCommands() {
        this.commands = SlashCommandRegistry.all();
    }

    @Override
    public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
        String buffer = line.line().stripLeading();
        if (!buffer.startsWith("/")) {
            return;
        }
        int wordIndex = line.wordIndex();
        String word = line.word();

        if (wordIndex == 0) {
            completeTopLevel(word, candidates);
        } else {
            completeSubCommand(buffer, word, candidates);
        }
    }

    private void completeTopLevel(String word, List<Candidate> candidates) {
        for (SlashCommand cmd : commands) {
            String name = cmd.name();
            if (!name.contains(" ") && name.startsWith(word)) {
                candidates.add(new Candidate(name, name, null, cmd.description(), null, null, true));
            }
        }
    }

    private void completeSubCommand(String buffer, String word, List<Candidate> candidates) {
        String parent = buffer.stripTrailing().split("\\s+", 2)[0];
        
        // Special handling for /plugins subcommands
        if ("/plugins".equals(parent) || "/plugin".equals(parent)) {
            String[] subCommands = {"list", "install", "uninstall", "enable", "disable", "validate", "info", "reload", "help"};
            for (String sub : subCommands) {
                if (sub.startsWith(word.toLowerCase(Locale.ROOT))) {
                    candidates.add(new Candidate(sub, sub, null, null, null, null, true));
                }
            }
            return;
        }

        // Special handling for /plugins install
        if (("/plugins install".equals(parent) || "/plugin install".equals(parent)
             || "/plugins add".equals(parent) || "/plugin add".equals(parent))
            && word.startsWith("--")) {
            String[] flags = {"--local", "--global"};
            for (String flag : flags) {
                if (flag.startsWith(word.toLowerCase(Locale.ROOT))) {
                    candidates.add(new Candidate(flag, flag, null, null, null, null, true));
                }
            }
            return;
        }
        
        for (SlashCommand cmd : commands) {
            String name = cmd.name();
            if (!name.contains(" ") || !parent.equals(parentOf(name))) {
                continue;
            }
            String sub = subCommandOf(name);
            if (sub.startsWith(word)) {
                candidates.add(new Candidate(sub, sub, null, cmd.description(), null, null, true));
            }
        }
    }
}
