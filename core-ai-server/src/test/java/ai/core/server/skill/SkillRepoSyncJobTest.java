package ai.core.server.skill;

import ai.core.server.domain.SkillDefinition;
import ai.core.server.domain.SkillRepoConfig;
import ai.core.server.domain.SkillSourceType;
import core.framework.mongo.MongoCollection;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
        var job = job(service, collection);

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
        var job = job(service, collection);

        job.execute(null);

        verify(service).syncFromRepoIfChanged("repo-1");
        verify(service).syncFromRepoIfChanged("repo-2");
    }

    private SkillRepoSyncJob job(SkillService service, MongoCollection<SkillDefinition> collection) {
        var job = new SkillRepoSyncJob();
        job.skillService = service;
        job.skillCollection = collection;
        return job;
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
