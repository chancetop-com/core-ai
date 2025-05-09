package ai.core.task.parts;

import ai.core.task.Part;
import ai.core.task.PartType;

/**
 * @author stephen
 */
public class FilePart extends Part<FilePart> {
    private final File file;

    public FilePart(File file) {
        super(PartType.FILE);
        this.file = file;
    }

    public File getFile() {
        return file;
    }

    public record File(String name, String mimeType, String bytes, String uri) {
    }
}
