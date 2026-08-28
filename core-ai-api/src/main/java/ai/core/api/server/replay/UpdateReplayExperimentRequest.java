package ai.core.api.server.replay;

import core.framework.api.json.Property;

/**
 * Saves the editable draft request and/or conclusion note of a replay experiment.
 *
 * @author stephen
 */
public class UpdateReplayExperimentRequest {
    @Property(name = "draft_request")
    public String draftRequest;

    @Property(name = "note")
    public String note;
}
