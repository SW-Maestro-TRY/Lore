package com.lore.webtoon.runs;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.webtoon.Admins;
import com.lore.webtoon.art.PageStore;
import com.lore.webtoon.credit.CreditGate;
import com.lore.webtoon.job.AfterRun;
import com.lore.webtoon.story.StoryStore;
import com.lore.webtoon.work.WorkLedger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 완성본의 제목·줄거리 고치기(#78) — 주인만 고칠 수 있고, 서버가 돌려준 값이
 * 앞으로 보일 값이다.
 */
class RunInfoEditTest {

    private final StoryStore stories = mock(StoryStore.class);
    private final WorkLedger ledger = mock(WorkLedger.class);
    private RunController controller;

    @BeforeEach
    void 세운다() {
        controller = new RunController(mock(RunService.class), mock(PageStore.class),
                mock(EpisodeExport.class), mock(OverlayStore.class), mock(BakeService.class),
                stories, mock(RegenService.class), mock(AfterRun.class), ledger,
                mock(CreditGate.class), mock(Admins.class));
    }

    @AfterEach
    void 치운다() {
        SecurityContextHolder.clearContext();
    }

    private static void 로그인(long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, null, List.of()));
    }

    @Test
    @DisplayName("주인이 줄거리를 고치면 서버가 정리한 줄거리를 돌려준다")
    void 주인은_줄거리를_고친다() {
        로그인(7L);
        when(ledger.mayChange("run-1", 7L)).thenReturn(true);
        when(stories.editPlot("run-1", "새 줄거리")).thenReturn("새 줄거리");

        ResponseEntity<Map<String, Object>> got = controller.logline("run-1", Map.of("logline", "새 줄거리"));

        assertThat(got.getStatusCode().value()).isEqualTo(200);
        assertThat(got.getBody()).containsEntry("logline", "새 줄거리");
    }

    @Test
    @DisplayName("주인이 제목을 고치면 서버가 정리한 제목을 돌려준다")
    void 주인은_제목을_고친다() {
        로그인(7L);
        when(ledger.mayChange("run-1", 7L)).thenReturn(true);
        when(stories.editTitle("run-1", "새 제목")).thenReturn("새 제목");

        ResponseEntity<Map<String, Object>> got = controller.title("run-1", Map.of("title", "새 제목"));

        assertThat(got.getStatusCode().value()).isEqualTo(200);
        assertThat(got.getBody()).containsEntry("title", "새 제목");
    }

    @Test
    @DisplayName("로그인하지 않으면 못 고친다")
    void 로그인_안_하면_401() {
        assertThatThrownBy(() -> controller.logline("run-1", Map.of("logline", "x")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED);
        assertThatThrownBy(() -> controller.title("run-1", Map.of("title", "x")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED);
        verify(stories, never()).editPlot(anyString(), anyString());
    }

    @Test
    @DisplayName("남의 작품은 못 고친다")
    void 남의_작품은_403() {
        로그인(7L);
        when(ledger.mayChange("run-1", 7L)).thenReturn(false);

        assertThatThrownBy(() -> controller.logline("run-1", Map.of("logline", "x")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
        verify(stories, never()).editPlot(anyString(), anyString());
    }

    @Test
    @DisplayName("아직 이야기를 안 고른 작품이면 404")
    void 안_고른_작품은_404() {
        로그인(7L);
        when(ledger.mayChange("run-1", 7L)).thenReturn(true);
        when(stories.editPlot("run-1", "x")).thenThrow(new NoSuchElementException());

        assertThat(controller.logline("run-1", Map.of("logline", "x")).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("logline 을 안 보내면 빈 값으로 읽는다 — 원래 줄거리로 되돌리기")
    void 빈_몸이면_비운다() {
        로그인(7L);
        when(ledger.mayChange("run-1", 7L)).thenReturn(true);
        when(stories.editPlot("run-1", "")).thenReturn("원래 줄거리");

        assertThat(controller.logline("run-1", Map.of()).getBody()).containsEntry("logline", "원래 줄거리");
    }
}
