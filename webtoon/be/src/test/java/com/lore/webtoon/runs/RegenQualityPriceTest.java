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
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 편집실 다시 그리기는 고른 화질의 값을 받는다 — 너울 작품도 이 장만 파도로 그리면 파도 값이다.
 * 실제 그림은 안 그린다(RegenService 는 흉내).
 */
class RegenQualityPriceTest {

    private final RegenService regen = mock(RegenService.class);
    private final CreditGate credits = mock(CreditGate.class);
    private final WorkLedger ledger = mock(WorkLedger.class);
    private RunController controller;

    @BeforeEach
    void 세운다() {
        controller = new RunController(mock(RunService.class), mock(PageStore.class), mock(EpisodeExport.class),
                mock(OverlayStore.class), mock(BakeService.class), mock(StoryStore.class), regen, mock(AfterRun.class),
                ledger, credits, mock(Admins.class), mock(com.lore.webtoon.safety.SafetyGuard.class));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(7L, null, List.of()));
        when(ledger.mayChange("r1", 7L)).thenReturn(true);
        when(regen.qualityOf("r1")).thenReturn("swell");
        when(regen.queue(anyString(), org.mockito.ArgumentMatchers.anyInt(), anyString(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn("g1");
        when(regen.statusOf("g1")).thenReturn(new HashMap<>());
    }

    @AfterEach
    void 치운다() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("너울 작품에서 파도를 고르면 3을 받고 파도로 그린다")
    void 낮추면_싸다() {
        controller.regen("r1", 2, Map.of("feedback", "", "quality", "surf"));

        verify(regen).queue("r1", 2, "", "surf", 7L);
        verify(credits).charge(eq(7L), eq(3), anyString(), anyString());
    }

    @Test
    @DisplayName("낸 사람을 기록에 적고 → 받고 → 줄에 넣는다 — 받기 전에 그리기 시작하지 않는다(#864)")
    void 받은_뒤에_줄에_넣는다() {
        controller.regen("r1", 2, Map.of("feedback", ""));

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(regen, credits);
        order.verify(regen).queue(eq("r1"), eq(2), anyString(), isNull(), eq(7L));
        order.verify(credits).charge(eq(7L), eq(6), eq("regen:r1:2:g1"), anyString());
        order.verify(regen).go("g1");
    }

    @Test
    @DisplayName("화질을 안 보내거나 모르는 값이면 작품 화질(너울) 값 6을 받는다")
    void 안_고르면_작품_값() {
        controller.regen("r1", 2, Map.of("feedback", ""));
        verify(credits).charge(eq(7L), eq(6), anyString(), anyString());

        controller.regen("r1", 3, Map.of("feedback", "", "quality", "high"));
        verify(regen).queue(eq("r1"), eq(3), anyString(), isNull(), eq(7L));
        verify(credits, org.mockito.Mockito.times(2)).charge(eq(7L), eq(6), anyString(), anyString());
    }
}
