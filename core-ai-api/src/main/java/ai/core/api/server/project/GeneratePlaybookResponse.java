package ai.core.api.server.project;

import core.framework.api.json.Property;

/**
 * Result of "generate playbook": a draft produced from the project definition, its members, the
 * tracked subjects and samples of recent material. It is NOT saved — the editor fills the draft in
 * and the user reviews it before saving.
 *
 * @author stephen
 */
public class GeneratePlaybookResponse {
    @Property(name = "playbook")
    public String playbook;
}
