package ai.core.server.skill;

import ai.core.api.server.skill.SkillTierView;
import ai.core.server.domain.SkillDefinition;
import ai.core.server.domain.SkillRepoConfig;
import ai.core.server.domain.SkillSourceType;
import ai.core.server.domain.SkillTier;
import core.framework.web.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SkillTiersTest {
    @Test
    void resolveReturnsExplicitTierFirst() {
        var def = new SkillDefinition();
        def.tier = SkillTier.ALWAYS_ON;
        def.namespace = "team";
        def.userId = "u1";
        def.sourceType = SkillSourceType.REPO;

        assertThat(SkillTiers.resolve(def)).isEqualTo(SkillTier.ALWAYS_ON);
    }

    @Test
    void resolveMapsBuiltinSystemToCore() {
        var def = new SkillDefinition();
        def.namespace = "builtin";
        def.userId = "system";
        def.sourceType = SkillSourceType.UPLOAD;

        assertThat(SkillTiers.resolve(def)).isEqualTo(SkillTier.CORE);
    }

    @Test
    void resolveMapsRepoSourceOrRepoConfigToImported() {
        var bySourceType = new SkillDefinition();
        bySourceType.namespace = "vendor";
        bySourceType.userId = "u1";
        bySourceType.sourceType = SkillSourceType.REPO;
        assertThat(SkillTiers.resolve(bySourceType)).isEqualTo(SkillTier.IMPORTED);

        var byRepoConfig = new SkillDefinition();
        byRepoConfig.namespace = "vendor";
        byRepoConfig.userId = "u1";
        byRepoConfig.sourceType = SkillSourceType.UPLOAD;
        byRepoConfig.repoConfig = new SkillRepoConfig();
        assertThat(SkillTiers.resolve(byRepoConfig)).isEqualTo(SkillTier.IMPORTED);
    }

    @Test
    void resolveDefaultsOthersToDomain() {
        var def = new SkillDefinition();
        def.namespace = "team";
        def.userId = "alice";
        def.sourceType = SkillSourceType.UPLOAD;

        assertThat(SkillTiers.resolve(def)).isEqualTo(SkillTier.DOMAIN);
        assertThat(SkillTiers.resolve(null)).isEqualTo(SkillTier.DOMAIN);
    }

    @Test
    void scopeLookupRejectsLegacySkillServerScope() {
        assertThat(SkillTiers.tierForScope(SkillTier.SCOPE_IMPORTED)).isEqualTo(SkillTier.IMPORTED);
        assertThat(SkillTiers.tierForScope(SkillTier.SCOPE_DOMAIN)).isEqualTo(SkillTier.DOMAIN);
        assertThat(SkillTiers.tierForScope(SkillTier.SCOPE_CORE)).isEqualTo(SkillTier.CORE);
        assertThat(SkillTiers.tierForScope(SkillTier.SCOPE_ALWAYS_ON)).isEqualTo(SkillTier.ALWAYS_ON);
        assertThat(SkillTiers.tierForScope("skill:server")).isNull();
        assertThatThrownBy(() -> SkillTiers.requireScope("skill:server"))
            .isInstanceOf(BadRequestException.class);
    }

    @Test
    void convertsBetweenEntityAndViewEnums() {
        for (var tier : SkillTier.values()) {
            SkillTierView view = SkillTiers.toView(tier);
            assertThat(view.name()).isEqualTo(tier.name());
            assertThat(SkillTiers.fromView(view)).isEqualTo(tier);
        }
    }
}
