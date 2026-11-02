package ai.core.session.transcript;

import ai.core.agent.AttachedContent;

import java.util.List;

/**
 * One user input of a turn: the text, its attachments and the client correlation id.
 *
 * @author stephen
 */
public record TranscriptInput(String text, List<AttachedContent> attachments, String clientTurnId) {
    public TranscriptInput {
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }
}
