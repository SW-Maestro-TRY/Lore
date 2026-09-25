package com.lore.webtoon.character;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 공유 카드 방문 보상의 세 규칙 — 주인은 안 세고, 같은 사람은 한 번, 카드마다 상한.
 */
class ShareRewardTest {

    private static final Instant NOW = Instant.parse("2026-09-26T03:00:00Z");

    private ShareVisitRepository visits;
    private ShareReward reward;
    private WebtoonCharacter card;

    @BeforeEach
    void setUp() {
        visits = mock(ShareVisitRepository.class);
        reward = new ShareReward(visits, "salt", Clock.fixed(NOW, ZoneOffset.UTC));
        // 계정 7 이 브라우저 "uowner" 로 만든 카드
        card = WebtoonCharacter.drawing("card1", 7L, "uowner", "몽이", "강아지", NOW);
    }

    @Test
    @DisplayName("남이 처음 열면 무료 횟수 한 번이 된다")
    void strangerFirstVisitRewards() {
        when(visits.existsByCharacterIdAndViewerKey("card1", "uid:ustranger")).thenReturn(false);
        when(visits.countByCharacterIdAndRewardedTrue("card1")).thenReturn(0L);

        assertThat(reward.opened(card, "ustranger", List.of("ustranger"), null, "1.2.3.4"))
                .isEqualTo(ShareReward.Outcome.REWARDED);

        ArgumentCaptor<ShareVisit> cap = ArgumentCaptor.forClass(ShareVisit.class);
        verify(visits).save(cap.capture());
        assertThat(cap.getValue().isRewarded()).isTrue();
        assertThat(cap.getValue().getViewerKey()).isEqualTo("uid:ustranger");
    }

    @Test
    @DisplayName("주인이 자기 링크를 열면 안 센다 — 브라우저로도, 계정으로도")
    void ownerNotCounted() {
        assertThat(reward.opened(card, "uowner", List.of("uowner"), null, "1.2.3.4"))
                .isEqualTo(ShareReward.Outcome.OWNER);
        assertThat(reward.opened(card, "uother", List.of("uother"), 7L, "1.2.3.4"))
                .isEqualTo(ShareReward.Outcome.OWNER);
        verify(visits, never()).save(any());
    }

    @Test
    @DisplayName("같은 사람이 다시 열면 한 번으로 친다")
    void sameViewerOnce() {
        when(visits.existsByCharacterIdAndViewerKey("card1", "uid:ustranger")).thenReturn(true);
        assertThat(reward.opened(card, "ustranger", List.of("ustranger"), null, "1.2.3.4"))
                .isEqualTo(ShareReward.Outcome.SEEN_BEFORE);
        verify(visits, never()).save(any());
    }

    @Test
    @DisplayName("카드 상한을 넘으면 세기만 하고 돌려주지 않는다")
    void capPerCard() {
        when(visits.existsByCharacterIdAndViewerKey(eq("card1"), anyString())).thenReturn(false);
        when(visits.countByCharacterIdAndRewardedTrue("card1")).thenReturn((long) ShareReward.CAP_PER_CARD);

        assertThat(reward.opened(card, "u4", List.of("u4"), null, "1.2.3.4"))
                .isEqualTo(ShareReward.Outcome.COUNTED_ONLY);
        ArgumentCaptor<ShareVisit> cap = ArgumentCaptor.forClass(ShareVisit.class);
        verify(visits).save(cap.capture());
        assertThat(cap.getValue().isRewarded()).isFalse();
    }

    @Test
    @DisplayName("브라우저 번호가 없으면 IP 해시로 가른다 — 주소 원문은 안 남는다")
    void ipHashWhenNoUid() {
        when(visits.existsByCharacterIdAndViewerKey(eq("card1"), anyString())).thenReturn(false);
        when(visits.countByCharacterIdAndRewardedTrue("card1")).thenReturn(0L);

        reward.opened(card, null, List.of(), null, "9.9.9.9");

        ArgumentCaptor<ShareVisit> cap = ArgumentCaptor.forClass(ShareVisit.class);
        verify(visits).save(cap.capture());
        assertThat(cap.getValue().getViewerKey()).startsWith("ip:").doesNotContain("9.9.9.9");
    }
}
