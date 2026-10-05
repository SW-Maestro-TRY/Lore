package com.lore.webtoon.runs;

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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.HashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 비공개 작품은 주인과 관리자만 연다(#638). 전에는 작품 번호만 알면 결과 · 그림 · 내려받기가 열렸다.
 */
class RunReadGateTest {

    private final RunService runs = mock(RunService.class);
    private final PageStore pages = mock(PageStore.class);
    private final WorkLedger ledger = mock(WorkLedger.class);
    private final Admins admins = mock(Admins.class);
    private RunController controller;

    @BeforeEach
    void 세운다() {
        controller = new RunController(runs, pages, mock(EpisodeExport.class), mock(OverlayStore.class),
                mock(BakeService.class), mock(StoryStore.class), mock(RegenService.class), mock(AfterRun.class), ledger,
                mock(CreditGate.class), admins, mock(com.lore.webtoon.safety.SafetyGuard.class));
        when(runs.result(anyString())).thenReturn(new HashMap<>());
        when(pages.urlOf(anyString(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn("/images/webtoon/x.jpg");
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
    @DisplayName("볼 수 없는 사람에게는 결과 · 장 · 내려받기 · 편집실 데이터 모두 404")
    void 남에게는_404() {
        when(ledger.mayRead("r1", null)).thenReturn(false);

        assertThat(controller.result("r1").getStatusCode().value()).isEqualTo(404);
        assertThat(controller.page("r1", 1, 1080, null).getStatusCode().value()).isEqualTo(404);
        assertThat(controller.episode("r1").getStatusCode().value()).isEqualTo(404);
        assertThat(controller.pageDownload("r1", 1).getStatusCode().value()).isEqualTo(404);
        assertThat(controller.episode("r1", 1).getStatusCode().value()).isEqualTo(404);
        verify(runs, never()).result("r1");
    }

    @Test
    @DisplayName("주인이면 열린다")
    void 주인은_열림() {
        로그인(7L);
        when(ledger.mayRead("r1", 7L)).thenReturn(true);

        assertThat(controller.result("r1").getStatusCode().value()).isEqualTo(200);
        assertThat(controller.page("r1", 1, 1080, null).getStatusCode().value()).isEqualTo(302);
    }

    @Test
    @DisplayName("관리자는 남의 비공개 작품도 연다")
    void 관리자는_열림() {
        로그인(1L);
        when(ledger.mayRead("r1", 1L)).thenReturn(false);
        when(admins.isAdmin(1L)).thenReturn(true);

        assertThat(controller.result("r1").getStatusCode().value()).isEqualTo(200);
    }
}
