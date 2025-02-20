package ai.core.example.api.naixt;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class NaixtChatRequest {
    @Property(name = "query")
    public String query;

    @Property(name = "current_file_path")
    public String currentFilePath;

    @Property(name = "current_line_number")
    public Integer currentLineNumber;

    @Property(name = "current_column_number")
    public Integer currentColumnNumber;
}
