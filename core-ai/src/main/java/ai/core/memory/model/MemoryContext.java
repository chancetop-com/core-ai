package ai.core.memory.model;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Context object containing retrieved memories for injection into agent prompt.
 *
 * @author xander
 */
public class MemoryContext {
    public static MemoryContext empty() {
        return new MemoryContext();
    }

    public static MemoryContext fromMemories(List<MemoryEntry> memories) {
        var semantic = memories.stream()
            .filter(m -> m.getType() == MemoryType.SEMANTIC)
            .toList();
        var episodic = memories.stream()
            .filter(m -> m.getType() == MemoryType.EPISODIC)
            .toList();
        return new MemoryContext(semantic, episodic);
    }

    private final List<MemoryEntry> semanticMemories;
    private final List<MemoryEntry> episodicMemories;

    public MemoryContext() {
        this.semanticMemories = new ArrayList<>();
        this.episodicMemories = new ArrayList<>();
    }

    public MemoryContext(List<MemoryEntry> semanticMemories, List<MemoryEntry> episodicMemories) {
        this.semanticMemories = semanticMemories != null ? semanticMemories : new ArrayList<>();
        this.episodicMemories = episodicMemories != null ? episodicMemories : new ArrayList<>();
    }

    public List<MemoryEntry> getSemanticMemories() {
        return semanticMemories;
    }

    public List<MemoryEntry> getEpisodicMemories() {
        return episodicMemories;
    }

    public List<MemoryEntry> getAllMemories() {
        var all = new ArrayList<MemoryEntry>();
        all.addAll(semanticMemories);
        all.addAll(episodicMemories);
        return all;
    }

    public boolean isEmpty() {
        return semanticMemories.isEmpty() && episodicMemories.isEmpty();
    }

    public int size() {
        return semanticMemories.size() + episodicMemories.size();
    }

    /**
     * Build a formatted context string for prompt injection.
     *
     * @return formatted memory context string
     */
    public String buildContextString() {
        if (isEmpty()) {
            return "";
        }

        var sb = new StringBuilder(256);

        if (!semanticMemories.isEmpty()) {
            sb.append("\n[User Memory]\n");
            for (var memory : semanticMemories) {
                sb.append("- ").append(memory.getContent()).append('\n');
            }
        }

        if (!episodicMemories.isEmpty()) {
            sb.append("\n[Relevant Experiences]\n");
            for (var memory : episodicMemories) {
                appendEpisodicEntry(sb, memory);
            }
        }

        return sb.toString();
    }

    private void appendEpisodicEntry(StringBuilder sb, MemoryEntry memory) {
        if (memory instanceof EpisodicMemoryEntry ep) {
            sb.append("- Situation: ").append(ep.getSituation());
            appendIfNotNull(sb, " | Action: ", ep.getAction());
            appendIfNotNull(sb, " | Outcome: ", ep.getOutcome());
            sb.append('\n');
        } else {
            sb.append("- ").append(memory.getContent()).append('\n');
        }
    }

    private void appendIfNotNull(StringBuilder sb, String prefix, String value) {
        if (value != null) {
            sb.append(prefix).append(value);
        }
    }

    /**
     * Build a concise summary of memories.
     *
     * @return concise summary string
     */
    public String buildSummary() {
        if (isEmpty()) {
            return "";
        }

        return getAllMemories().stream()
            .map(MemoryEntry::getContent)
            .collect(Collectors.joining("; "));
    }
}
