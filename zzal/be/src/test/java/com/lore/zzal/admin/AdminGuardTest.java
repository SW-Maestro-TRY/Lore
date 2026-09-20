package com.lore.zzal.admin;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.common.user.User;
import com.lore.common.user.UserRepository;
import com.lore.common.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 관리자 판정 본체 — <b>이 판정 자체는 한 번도 안 돌았다</b>(M-26).
 *
 * <h3>★ 왜 위험한가</h3>
 * {@code AdminServiceTest} 는 {@link AdminGuard} 를 언제나 목으로 쓴다. 그래서 "모든 길이 막혀 있다" 는
 * 시험은 있어도 <b>막는 판정 자체</b>는 한 줄도 안 돌았다 — {@code !user.isAdmin()} 의 느낌표가
 * 사라져도 시험이 전부 초록이다. 이 판정은 "스위치가 켜진 환경에서 로그인한 아무나가
 * <b>남의 움짤을 보는 것</b>" 을 막는 유일한 층이다.
 */
@DisplayName("관리자 판정 — 막는 쪽이 실제로 막는가")
class AdminGuardTest {

    private static final Long USER_ID = 1L;

    private UserRepository userRepository;
    private AdminGuard guard;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        guard = new AdminGuard(userRepository);
    }

    private User user(boolean admin) {
        User u = User.signUp("zzal-admin-test@example.invalid");
        if (admin) {
            ReflectionTestUtils.setField(u, "role", UserRole.ADMIN);
        }
        return u;
    }

    @Test
    @DisplayName("★★ 관리자가 아니면 ADMIN_ONLY — 여기가 뒤집히면 로그인한 아무나가 남의 움짤을 본다")
    void plainUserIsRefused() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(false)));

        assertThatThrownBy(() -> guard.require(USER_ID))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ADMIN_ONLY);
    }

    @Test
    @DisplayName("★ 없는 사용자 번호도 ADMIN_ONLY — '그런 사람 없다' 를 알려주지 않는다")
    void missingUserIsRefused() {
        when(userRepository.findById(anyLong())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> guard.require(USER_ID))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ADMIN_ONLY);
    }

    @Test
    @DisplayName("관리자는 통과한다 — 막기만 하고 못 들어가면 검수를 아무도 못 한다")
    void adminPasses() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(true)));

        assertThatCode(() -> guard.require(USER_ID)).doesNotThrowAnyException();
    }
}
