package ai.core.server.skill;

import ai.core.server.domain.SkillDefinition;
import ai.core.server.domain.SkillSourceType;
import ai.core.server.domain.SkillTier;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class PresetSkillSeederTest {
    @Test
    void seedsSevenBuiltinSkillsAsCoreAndIsIdempotentOnSecondRun() {
        var store = new InMemorySkillStore();
        var seeder = new PresetSkillSeeder();
        seeder.skillStore = store;

        seeder.seed();

        assertThat(store.rows).hasSize(7);
        for (var name : PresetSkillSeeder.PRESET_NAMES) {
            var skill = store.get(PresetSkillSeeder.skillId(name)).orElseThrow();
            assertThat(skill.namespace).isEqualTo("builtin");
            assertThat(skill.name).isEqualTo(name);
            assertThat(skill.qualifiedName).isEqualTo("builtin/" + name);
            assertThat(skill.userId).isEqualTo("system");
            assertThat(skill.tier).isEqualTo(SkillTier.CORE);
            assertThat(skill.revision).isEqualTo(1);
            assertThat(skill.digest).isNotBlank();
            assertThat(skill.metadata).containsEntry("author", "core-ai");
            assertThat(skill.metadata).containsEntry("version", "1.0.0");
            assertThat(skill.metadata.get("tags")).isNotBlank();
        }

        var firstTimestamps = new LinkedHashMap<String, Object>();
        store.rows.forEach((id, def) -> firstTimestamps.put(id, def.updatedAt));
        int replaceCountBefore = store.replaceCount;

        seeder.seed();

        assertThat(store.rows).hasSize(7);
        assertThat(store.replaceCount).isEqualTo(replaceCountBefore);
        for (var entry : firstTimestamps.entrySet()) {
            var after = store.get(entry.getKey()).orElseThrow();
            assertThat(after.revision).isEqualTo(1);
            assertThat(after.updatedAt).isEqualTo(entry.getValue());
        }
    }

    @Test
    void updatesUntouchedPresetWhenContentDigestChangesAndPreservesAdminEditedPreset() {
        var store = new InMemorySkillStore();
        var seeder = new PresetSkillSeeder();
        seeder.skillStore = store;

        seeder.seed();
        var camera = store.get("skill-ai-camera-language").orElseThrow();
        var originalRevision = camera.revision;

        var outcome = seeder.seedOne("ai-camera-language", "---\nname: ai-camera-language\ndescription: Updated camera language.\n---\n# New body\n");
        assertThat(outcome).isEqualTo(PresetSkillSeeder.SeedOutcome.UPDATED);
        var updated = store.get("skill-ai-camera-language").orElseThrow();
        assertThat(updated.revision).isEqualTo(originalRevision + 1);
        assertThat(updated.tier).isEqualTo(SkillTier.CORE);
        assertThat(updated.description).isEqualTo("Updated camera language.");

        // Simulate admin edit: digest diverges from preset_seed_digest
        updated.content = "---\nname: ai-camera-language\ndescription: Admin custom copy.\n---\n# Admin edit\n";
        updated.digest = SkillDigest.of(updated.content, null);
        store.replace(updated);

        var skipped = seeder.seedOne("ai-camera-language", "---\nname: ai-camera-language\ndescription: Another release.\n---\n# Release 2\n");
        assertThat(skipped).isEqualTo(PresetSkillSeeder.SeedOutcome.UNCHANGED);
        assertThat(store.get("skill-ai-camera-language").orElseThrow().content).contains("Admin edit");
    }

    private static final class InMemorySkillStore implements SkillStore {
        private final Map<String, SkillDefinition> rows = new LinkedHashMap<>();
        private int replaceCount;

        @Override
        public Optional<SkillDefinition> get(String id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public Optional<SkillDefinition> findByQualifiedName(String qualifiedName) {
            return rows.values().stream().filter(s -> qualifiedName.equals(s.qualifiedName)).findFirst();
        }

        @Override
        public List<SkillDefinition> findByName(String name) {
            return rows.values().stream().filter(s -> name.equals(s.name)).toList();
        }

        @Override
        public List<SkillDefinition> listByIds(Collection<String> ids) {
            return rows.values().stream().filter(s -> ids.contains(s.id)).toList();
        }

        @Override
        public List<SkillDefinition> list(String namespace, SkillSourceType sourceType, Integer offset, Integer limit) {
            return List.copyOf(rows.values());
        }

        @Override
        public List<SkillDefinition> listSummaries(String namespace, SkillSourceType sourceType, Integer offset, Integer limit) {
            return List.copyOf(rows.values());
        }

        @Override
        public Map<String, String> findNames(Collection<String> ids) {
            return Map.of();
        }

        @Override
        public long count(String namespace, SkillSourceType sourceType) {
            return rows.size();
        }

        @Override
        public List<SkillDefinition> listBySourceType(SkillSourceType sourceType) {
            return rows.values().stream().filter(s -> s.sourceType == sourceType).toList();
        }

        @Override
        public List<SkillDefinition> listAll() {
            return List.copyOf(rows.values());
        }

        @Override
        public List<SkillDefinition> listCatalog() {
            return List.copyOf(rows.values());
        }

        @Override
        public long countByNamespace(String namespace) {
            return rows.values().stream().filter(s -> namespace.equals(s.namespace)).count();
        }

        @Override
        public long countBySourceType(SkillSourceType sourceType) {
            return rows.values().stream().filter(s -> s.sourceType == sourceType).count();
        }

        @Override
        public long countByTier(SkillTier tier) {
            return rows.values().stream().filter(s -> s.tier == tier).count();
        }

        @Override
        public List<SkillDefinition> listByTier(SkillTier tier) {
            return rows.values().stream().filter(s -> s.tier == tier).toList();
        }

        @Override
        public void insert(SkillDefinition definition) {
            rows.put(definition.id, definition);
        }

        @Override
        public boolean insertIfAbsent(SkillDefinition definition) {
            return rows.putIfAbsent(definition.id, definition) == null;
        }

        @Override
        public void replace(SkillDefinition definition) {
            rows.put(definition.id, definition);
            replaceCount++;
        }

        @Override
        public boolean replaceIfRevision(SkillDefinition definition, int expectedRevision) {
            var current = rows.get(definition.id);
            int currentRev = current == null || current.revision == null ? 0 : current.revision;
            if (currentRev != expectedRevision) return false;
            rows.put(definition.id, definition);
            replaceCount++;
            return true;
        }

        @Override
        public void delete(String id) {
            rows.remove(id);
        }

        @Override
        public void recordShow(String qualifiedName) {
        }

        @Override
        public void recordPull(String id) {
        }
    }
}
