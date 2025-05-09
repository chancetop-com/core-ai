package ai.core.task.parts;

import ai.core.task.Part;

/**
 * @author stephen
 */
public class FilePart extends Part<FilePart> {
    public File file;

    public static class File {
        public String name;
        public String mimeType;
        public String bytes;
        public String uri;
    }
}
