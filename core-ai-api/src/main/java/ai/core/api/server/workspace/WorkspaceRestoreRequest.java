package ai.core.api.server.workspace;

import core.framework.api.json.Property;
import core.framework.api.validate.NotBlank;
import core.framework.api.validate.NotNull;

/**
 * @author stephen
 */
public class WorkspaceRestoreRequest {
    @NotNull
    @NotBlank
    @Property(name = "archive_id")
    public String archiveId;

    @Property(name = "new_name")
    public String newName;

    @Property(name = "allow_overwrite")
    public Boolean allowOverwrite;
}
