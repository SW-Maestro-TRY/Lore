package com.lore.webtoon.work;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.webtoon.Admins;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 관리자 작품 처리 API(#638) — 관리자만. 아니면 아무것도 안 한다.
 */
class ModerationControllerTest {

    private Admins admins;
    private WorkModeration moderation;
    private ModerationController controller;

    @BeforeEach
    void 세운다() {
        admins = mock(Admins.class);
        moderation = mock(WorkModeration.class);
        controller = new ModerationController(admins, moderation);
    }

    @AfterEach
    void 치운다() {
        SecurityContextHolder.clearContext();
    }

    private static void 로그인(long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, null, List.of()));
    }

    private static ErrorCode 코드(Throwable e) {
        return ((BusinessException) e).getErrorCode();
    }

    private static final ModerationController.ReasonRequest 사유 = new ModerationController.ReasonRequest("사유");

    @Test
    @DisplayName("로그인 안 했으면 401")
    void 로그인_안_했으면_401() {
        assertThatThrownBy(() -> controller.hide("r1", 사유)).extracting(ModerationControllerTest::코드).isEqualTo(ErrorCode.UNAUTHORIZED);
        assertThatThrownBy(() -> controller.log(10)).extracting(ModerationControllerTest::코드).isEqualTo(ErrorCode.UNAUTHORIZED);
        verify(moderation, never()).hide(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("관리자가 아니면 403 — 처리 · 보기 모두")
    void 관리자가_아니면_403() {
        로그인(7L);
        when(admins.isAdmin(7L)).thenReturn(false);

        assertThatThrownBy(() -> controller.hide("r1", 사유)).extracting(ModerationControllerTest::코드).isEqualTo(ErrorCode.FORBIDDEN);
        assertThatThrownBy(() -> controller.remove("r1", 사유)).extracting(ModerationControllerTest::코드).isEqualTo(ErrorCode.FORBIDDEN);
        assertThatThrownBy(() -> controller.warn("r1", 사유)).extracting(ModerationControllerTest::코드).isEqualTo(ErrorCode.FORBIDDEN);
        assertThatThrownBy(() -> controller.unhide("r1", null)).extracting(ModerationControllerTest::코드).isEqualTo(ErrorCode.FORBIDDEN);
        assertThatThrownBy(() -> controller.restore("r1", null)).extracting(ModerationControllerTest::코드).isEqualTo(ErrorCode.FORBIDDEN);
        assertThatThrownBy(() -> controller.state("r1")).extracting(ModerationControllerTest::코드).isEqualTo(ErrorCode.FORBIDDEN);
        assertThatThrownBy(() -> controller.removed()).extracting(ModerationControllerTest::코드).isEqualTo(ErrorCode.FORBIDDEN);
        assertThatThrownBy(() -> controller.owners()).extracting(ModerationControllerTest::코드).isEqualTo(ErrorCode.FORBIDDEN);
        verify(moderation, never()).hide(any(), anyString(), anyString());
        verify(moderation, never()).remove(any(), anyString(), anyString());
        verify(moderation, never()).warn(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("관리자면 처리한 사람 번호를 실어 넘긴다")
    void 관리자면_처리() {
        로그인(1L);
        when(admins.isAdmin(1L)).thenReturn(true);

        controller.hide("r1", 사유);

        verify(moderation).hide(1L, "r1", "사유");
    }
}
