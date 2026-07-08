package ai.core.cli.ui;

import ai.core.agent.profile.AgentProfile;
import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;

import java.util.ArrayList;
import java.util.List;

/**
 * JLine completer that triggers on @ to suggest available agent names.
 *
 * @author lim chen
 */
public class AgentNameCompleter implements Completer {

    private volatile List<String> agentNames = List.of();

    public void setAgentNames(List<String> names) {
        this.agentNames = List.copyOf(names);
    }

    @Override
    public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
        String word = line.word().substring(0, line.wordCursor());
        if (!word.startsWith("@")) {
            return;
        }
        String prefix = word.substring(1).toLowerCase();
        for (String name : agentNames) {
            if (name.toLowerCase().startsWith(prefix)) {
                candidates.add(new Candidate("@" + name, name, null, null, null, null, true));
            }
        }
    }
}
