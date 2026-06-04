package ai.core.server.skill;

import ai.core.server.domain.SkillDefinition;
import core.framework.mongo.MongoCollection;
import core.framework.web.exception.NotFoundException;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SkillServiceTest {
    @Test
    void listFiltersQueryInMemory() {
        var service = new SkillService();
        service.skillCollection = skillCollection();

        var matchingName = skill("1", "Admin", "seo-audit", null);
        var matchingDescription = skill("2", "Admin", "report", "review report skill");
        var nonMatching = skill("3", "Admin", "content", "content writer");
        when(service.skillCollection.find(any(Bson.class))).thenReturn(List.of(matchingName, matchingDescription, nonMatching));

        var result = service.list(null, null, "audit");

        assertEquals(List.of(matchingName), result);
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
