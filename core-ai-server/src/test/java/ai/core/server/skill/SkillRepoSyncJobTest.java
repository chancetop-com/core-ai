package ai.core.server.skill;

import ai.core.server.domain.SkillDefinition;
import ai.core.server.domain.SkillRepoConfig;
import ai.core.server.domain.SkillSourceType;
import ai.core.server.settings.SystemSettingsService;
import core.framework.mongo.MongoCollection;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillRepoSyncJobTest {
    private static final String REPO_URL = "https://github.com/wuyoscar/GPT-Image2-Skill";

    @Test
    void syncsRepoSkillsAndDefersARepoThatKeepsFailing() {
        var collection = skillCollection();
        when(collection.find(any(Bson.class))).thenReturn(List.of(repoSkill()));
        var service = mock(SkillService.class);
        when(service.syncFromRepoIfChanged("repo-1")).thenThrow(new RuntimeException("cannot read remote head"));
        // interval 0 keeps every tick due so the backoff behavior is isolated from the interval throttle
        var job = job(service, collection, settingsService(true, 0));

        job.execute(null);
        job.execute(null);

        verify(service, times(2)).sweepStaleRepoTempDirs();
        verify(service, times(1)).syncFromRepoIfChanged("repo-1");
    }

    @Test
    void syncsEveryRepoSkillInTheCatalog() {
        var collection = skillCollection();
        when(collection.find(any(Bson.class))).thenReturn(List.of(repoSkill(), repoSkill("repo-2")));
        var service = mock(SkillService.class);
        var job = job(service, collection, settingsService(true, 30));

        job.execute(null);

        verify(service).syncFromRepoIfChanged("repo-1");
        verify(service).syncFromRepoIfChanged("repo-2");
    }

    @Test
    void scheduledSyncStopsWhenDisabledButTempDirHousekeepingContinues() {
        var collection = skillCollection();
        var service = mock(SkillService.class);
        var job = job(service, collection, settingsService(false, 30));

        job.execute(null);

        verify(service, times(1)).sweepStaleRepoTempDirs();
        verify(service, never()).syncFromRepoIfChanged(any());
        verify(collection, never()).find(any(Bson.class));
    }

    @Test
    void waitsForTheConfiguredIntervalBetweenSweeps() {
        var collection = skillCollection();
        when(collection.find(any(Bson.class))).thenReturn(List.of(repoSkill()));
        var service = mock(SkillService.class);
        var job = job(service, collection, settingsService(true, 30));

        job.execute(null);
        job.execute(null);

        verify(service, times(1)).syncFromRepoIfChanged("repo-1");
    }

    private SkillRepoSyncJob job(SkillService service, MongoCollection<SkillDefinition> collection, SystemSettingsService settings) {
        var job = new SkillRepoSyncJob();
        job.skillService = service;
        job.skillCollection = collection;
        job.systemSettingsService = settings;
        return job;
    }

    private SystemSettingsService settingsService(boolean enabled, int intervalMinutes) {
        var settings = mock(SystemSettingsService.class);
        when(settings.skillRepoSyncEnabled()).thenReturn(enabled);
        when(settings.skillRepoSyncIntervalMinutes()).thenReturn(intervalMinutes);
        return settings;
    }

    private SkillDefinition repoSkill() {
        return repoSkill("repo-1");
    }

    private SkillDefinition repoSkill(String id) {
        var skill = new SkillDefinition();
        skill.id = id;
        skill.namespace = "wuyoscar";
        skill.name = "gpt-image";
        skill.qualifiedName = "wuyoscar/gpt-image";
        skill.sourceType = SkillSourceType.REPO;
        skill.userId = "admin@example.com";
        var config = new SkillRepoConfig();
        config.repoUrl = REPO_URL;
        config.branch = "main";
        skill.repoConfig = config;
        return skill;
    }

    @SuppressWarnings("unchecked")
    private MongoCollection<SkillDefinition> skillCollection() {
        return (MongoCollection<SkillDefinition>) mock(MongoCollection.class);
    }
}
