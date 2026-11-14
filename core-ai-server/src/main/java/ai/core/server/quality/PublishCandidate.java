package ai.core.server.quality;

import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentPublishedConfig;

import java.util.List;

/**
 * The exact content a publish would commit, built once. Evaluation reads this object and the commit writes
 * this object, so "checked" and "published" cannot drift through a second parse of the managed prompt.
 *
 * @author core-ai
 */
public record PublishCandidate(AgentDefinition entity,
                               AgentPublishedConfig config,
                               String name,
                               String description,
                               String executionCandidateHash,
                               String metadataHash,
                               int definitionRevision,
                               QualityPromptView prompt,
                               List<QualityPromptLine> promptLines,
                               String promptField,
                               String promptSource) {
}
