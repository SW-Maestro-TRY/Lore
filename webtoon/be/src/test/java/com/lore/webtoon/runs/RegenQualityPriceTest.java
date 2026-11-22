package com.lore.webtoon.runs;

import com.lore.webtoon.lou.EditorRegen;
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
        PageStore pageStore = mock(PageStore.class);
        when(pageStore.pageNumbersOf("r1")).thenReturn(List.of(1, 2, 3));
        controller = new RunController(mock(RunService.class), pageStore, mock(EpisodeExport.class),
                mock(OverlayStore.class), mock(BakeService.class), mock(StoryStore.class), regen, mock(AfterRun.class),
                ledger, credits, mock(Admins.class), mock(com.lore.webtoon.safety.SafetyGuard.class), new EditorRegen(regen, credits, mock(com.lore.webtoon.lou.LouRecords.class)));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(7L, null, List.of()));
        when(ledger.mayChange("r1", 7L)).thenReturn(true);
        when(regen.qualityOf("r1")).thenReturn("swell");
        when(regen.queue(anyString(), org.mockito.ArgumentMatchers.anyInt(), anyString(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), isNull(), eq(false)))
                .thenReturn("g1");
        when(regen.statusOf("g1")).thenReturn(new HashMap<>());
    }

    @Test
    void keepRequiresNoteAndCannotBeTextless() {
        for (Map<String, Object> body : List.<Map<String, Object>>of(
                Map.of("mode", "keep", "feedback", "  "),
                Map.of("mode", "keep", "feedback", "소매", "textless", true))) {
            org.junit.jupiter.api.Assertions.assertEquals(400, controller.regen("r1", 2, body).getStatusCode().value());
        }
        org.mockito.Mockito.verifyNoInteractions(credits);
    }

    @Test
    void forwardsRegenMode() {
        controller.regen("r1", 2, Map.of("mode", "keep", "feedback", "소매"));
        verify(regen).queue("r1", 2, "소매", null, 7L, "keep", null, false);
        controller.regen("r1", 2, Map.of("textless", true));
        verify(regen).queue("r1", 2, "", null, 7L, "textless", null, false);
    }

    @Test
    void 글자만_고치기는_무료이고_형식이_틀리면_안_받는다() {
        var item = Map.<String, Object>of("x", 51, "y", 82, "text", " 맞는 글 ");
        controller.regen("r1", 2, Map.of("mode", "text", "items", List.of(item)));
        var note = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(regen).queue(eq("r1"), eq(2), note.capture(), isNull(), isNull(), eq("text"), isNull(), eq(false));
        org.assertj.core.api.Assertions.assertThat(note.getValue()).contains("\"text\":\"맞는 글\"", "\"x\":51");
        org.mockito.Mockito.verifyNoInteractions(credits);

        for (Object items : List.of(List.of(), "글", List.of(Map.of("x", 101, "y", 2, "text", "글")),
                List.of(Map.of("x", 1, "y", 2, "text", " ")))) {
            org.junit.jupiter.api.Assertions.assertEquals(400,
                    controller.regen("r1", 2, Map.of("mode", "text", "items", items)).getStatusCode().value());
        }
        org.junit.jupiter.api.Assertions.assertEquals(404,
                controller.regen("r1", 9, Map.of("mode", "text", "items", List.of(item))).getStatusCode().value());
        when(regen.drawingPages("r1")).thenReturn(List.of(2));
        org.junit.jupiter.api.Assertions.assertEquals(409,
                controller.regen("r1", 2, Map.of("mode", "text", "items", List.of(item))).getStatusCode().value());
    }

    @AfterEach
    void 치운다() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("너울 작품에서 파도를 고르면 3을 받고 파도로 그린다")
    void 낮추면_싸다() {
        controller.regen("r1", 2, Map.of("feedback", "", "quality", "surf"));

        verify(regen).queue("r1", 2, "", "surf", 7L, null, null, false);
        verify(credits).charge(eq(7L), eq(3), anyString(), anyString());
    }

    @Test
    @DisplayName("낸 사람을 기록에 적고 → 받고 → 줄에 넣는다 — 받기 전에 그리기 시작하지 않는다(#864)")
    void 받은_뒤에_줄에_넣는다() {
        controller.regen("r1", 2, Map.of("feedback", ""));

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(regen, credits);
        order.verify(regen).queue(eq("r1"), eq(2), anyString(), isNull(), eq(7L), isNull(), isNull(), eq(false));
        order.verify(credits).charge(eq(7L), eq(6), eq("regen:r1:2:g1"), anyString());
        order.verify(regen).go("g1");
    }

    @Test
    @DisplayName("화질을 안 보내거나 모르는 값이면 작품 화질(너울) 값 6을 받는다")
    void 안_고르면_작품_값() {
        controller.regen("r1", 2, Map.of("feedback", ""));
        verify(credits).charge(eq(7L), eq(6), anyString(), anyString());

        controller.regen("r1", 3, Map.of("feedback", "", "quality", "high"));
        verify(regen).queue(eq("r1"), eq(3), anyString(), isNull(), eq(7L), isNull(), isNull(), eq(false));
        verify(credits, org.mockito.Mockito.times(2)).charge(eq(7L), eq(6), anyString(), anyString());
    }
}
