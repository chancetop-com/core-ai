package ai.core.server.skill;

import ai.core.server.domain.SkillDefinition;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import core.framework.web.exception.NotFoundException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillServiceTest {
    @Test
    void listAppliesPagingToQuery() {
        var service = new SkillService();
        service.skillCollection = skillCollection();

        var matchingName = skill("1", "Admin", "seo-audit", null);
        when(service.skillCollection.find(any(Query.class))).thenReturn(List.of(matchingName));

        var result = service.list(null, null, null, "audit", 20, 10);

        assertEquals(List.of(matchingName), result);
        var query = ArgumentCaptor.forClass(Query.class);
        verify(service.skillCollection).find(query.capture());
        assertEquals(20, query.getValue().skip);
        assertEquals(10, query.getValue().limit);
    }

    @Test
    void getThrowsNotFoundForMissingSkill() {
        var service = new SkillService();
        service.skillCollection = skillCollection();
        when(service.skillCollection.get("missing")).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> service.get("missing"));
    }

    @SuppressWarnings("unchecked")
    private MongoCollection<SkillDefinition> skillCollection() {
        return (MongoCollection<SkillDefinition>) mock(MongoCollection.class);
    }

    private SkillDefinition skill(String id, String namespace, String name, String description) {
        var skill = new SkillDefinition();
        skill.id = id;
        skill.namespace = namespace;
        skill.name = name;
        skill.description = description;
        return skill;
    }
}
