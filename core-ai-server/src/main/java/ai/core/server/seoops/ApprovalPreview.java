package ai.core.server.seoops;

import ai.core.server.seoops.domain.SeoEvidenceState;
import ai.core.server.seoops.domain.SeoTaskStatus;

import java.util.List;

/**
 * @author xander
 */
record ApprovalPreview(boolean reviewable, List<String> blockers, long taskRevision,
                       long stateVersion, String executionSpecHash,
                       SeoEvidenceState evidenceState, SeoTaskStatus currentStatus) {
}
