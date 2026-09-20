package com.lore.zzal.agent;

import com.lore.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 맥미니 전용 열쇠 — <b>만료가 없다는 것은 새면 영원히 열린다는 뜻</b>이다.
 *
 * <h3>★ 왜 이 시험들이 필요한가</h3>
 * 만료 없는 열쇠는 편한 만큼 위험하다. 편의를 위해 하나씩 느슨하게 하다 보면
 * <b>기본 열쇠가 운영에 올라가</b> 있고 아무도 모른다. 세 가지를 코드로 못박는다.
 */
@DisplayName("러너 열쇠 — 만료가 없으니 더 깐깐하게")
class AgentGuardTest {

    private static final String GOOD = "0123456789abcdef0123456789abcdef";   // 32자

    @Test
    @DisplayName("★★ 설정이 비어 있으면 문 자체가 안 열린다 — 기본 열쇠를 두지 않는다")
    void disabledWhenUnset() {
        AgentGuard guard = new AgentGuard("", 0);

        assertThat(guard.enabled()).isFalse();
        assertThatThrownBy(() -> guard.require(GOOD)).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("★ 짧은 열쇠는 기동에서 막는다 — 짧은 열쇠는 열쇠가 아니다")
    void rejectsShortKeyAtStartup() {
        assertThatThrownBy(() -> new AgentGuard("short", 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("너무 짧");
    }

    @Test
    @DisplayName("★ 열쇠를 켰으면 주인도 있어야 한다 — 주인 없는 업로드 경로를 만들지 않는다")
    void rejectsMissingOwner() {
        assertThatThrownBy(() -> new AgentGuard(GOOD, 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("user-id");
    }

    @Test
    @DisplayName("맞는 열쇠는 그 열쇠의 주인을 돌려준다")
    void returnsOwner() {
        AgentGuard guard = new AgentGuard(GOOD, 42L);

        assertThat(guard.require(GOOD)).isEqualTo(42L);
        assertThatCode(() -> guard.require("  " + GOOD + "  ")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("★★ 열쇠가 없거나 틀리면 401 — 길이가 달라도 같은 길로 거절한다")
    void rejectsWrongKey() {
        AgentGuard guard = new AgentGuard(GOOD, 42L);

        assertThatThrownBy(() -> guard.require(null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> guard.require("")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> guard.require("x")).isInstanceOf(BusinessException.class);
        // 한 글자만 다른 경우 — 앞에서부터 맞춰 가며 알아낼 수 없어야 한다
        assertThatThrownBy(() -> guard.require(GOOD.substring(0, 31) + "0"))
                .isInstanceOf(BusinessException.class);
    }
}
