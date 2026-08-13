package ai.core.api.server.foryou;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class ListForYouFilesResponse {
    @Property(name = "files")
    public List<ForYouFileView> files;
}
