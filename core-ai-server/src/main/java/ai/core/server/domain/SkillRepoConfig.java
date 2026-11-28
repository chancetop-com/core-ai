package ai.core.server.domain;

import core.framework.mongo.Field;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class SkillRepoConfig {
    @Field(name = "repo_url")
    public String repoUrl;

    @Field(name = "branch")
    public String branch;

    @Field(name = "skill_path")
    public String skillPath;

    @Field(name = "last_synced_at")
    public ZonedDateTime lastSyncedAt;

    @Field(name = "last_commit_hash")
    public String lastCommitHash;

    @Field(name = "last_sync_status")
    public String lastSyncStatus;

    @Field(name = "last_rejected_commit_hash")
    public String lastRejectedCommitHash;

    @Field(name = "last_sync_error")
    public String lastSyncError;
}
