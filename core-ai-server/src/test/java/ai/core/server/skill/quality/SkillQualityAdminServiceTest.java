package ai.core.server.skill.quality;

import ai.core.server.domain.User;
import ai.core.server.user.UserStore;
import core.framework.web.exception.ForbiddenException;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SkillQualityAdminServiceTest {
    private final UserStore userStore = mock(UserStore.class);
    private final SkillQualityAdminService service = service();

    @Test
    void returnsTheCodeDefinedCatalogToAnAdmin() {
        when(userStore.get("admin-1")).thenReturn(Optional.of(user("admin", "internal")));

        var response = service.rules("admin-1");

        assertThat(response.policyRevision).isEqualTo(SkillQualityGate.POLICY_REVISION);
        assertThat(response.rules).hasSize(10);
        assertThat(response.rules).extracting(view -> view.key)
            .containsExactlyElementsOf(SkillQualityGate.ruleKeys());
        assertThat(response.rules).allSatisfy(view -> {
            assertThat(view.name).isNotBlank();
            assertThat(view.category).isNotBlank();
            assertThat(view.enforcement).isNotBlank();
            assertThat(view.requirement).isNotBlank();
            assertThat(view.revision).isPositive();
        });
    }

    @Test
    void rejectsNonAdminUsers() {
        when(userStore.get("user-1")).thenReturn(Optional.of(user("user", "internal")));
        when(userStore.get("api-1")).thenReturn(Optional.of(user("admin", "api")));

        assertThatThrownBy(() -> service.rules("user-1")).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.rules("api-1")).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.rules("missing")).isInstanceOf(ForbiddenException.class);
    }

    private SkillQualityAdminService service() {
        var service = new SkillQualityAdminService();
        service.userStore = userStore;
        return service;
    }

    private User user(String role, String userType) {
        var user = new User();
        user.role = role;
        user.userType = userType;
        return user;
    }
}
