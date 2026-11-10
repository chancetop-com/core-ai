package ai.core.server.file;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * @author stephen
 */
public class FileRecordQuery {
    public String userId;
    public List<String> ids;
    public List<String> contentHashes;
    public String shareToken;
    public Boolean sharedOnly;
    public List<String> fileNames;
    public String fileNameKeyword;
    public String contentType;

    /** strict lower bound */
    public ZonedDateTime createdAfter;

    /** Mongo-only: legacy records whose content is still inline */
    public boolean legacyInlineOnly;

    /** list surfaces must not pull legacy base64 payloads */
    public boolean excludeData;

    /** created_at | shared_at */
    public String sortField = "created_at";
    public boolean ascending;
    public int offset;
    public int limit = 100;
}
