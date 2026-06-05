package ai.core.server.domain;

import core.framework.mongo.MongoEnumValue;

/**
 * The run mode of a workflow, shared by one engine. WORKFLOW is single-shot; CHATFLOW is session-stateful
 * (conversation variables persist across runs on the ChatSession).
 *
 * @author Xander
 */
public enum WorkflowMode {
    @MongoEnumValue("WORKFLOW")
    WORKFLOW,
    @MongoEnumValue("CHATFLOW")
    CHATFLOW
}
