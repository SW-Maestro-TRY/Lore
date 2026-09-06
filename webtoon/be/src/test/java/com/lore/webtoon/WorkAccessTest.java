package com.lore.webtoon;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 비공개 작품을 누가 볼 수 있나.
 *
 * <b>이 파일이 지키는 것 하나</b>: 비공개는 주인만 본다. 지금까지는 목록에서
 * 가려질 뿐이라 작품 번호만 알면 누구나 그림을 받을 수 있었다(실측으로 확인).
 * 그 구멍이 다시 열리면 여기서 걸린다.
 */
class WorkAccessTest {

    private static final long ME = 7L;
    private static final long 남 = 9L;

    private final List<WebtoonWork> rows = new ArrayList<>();
    private final List<String> linkedUids = new ArrayList<>();
    private WorkLedger ledger;

    @BeforeEach
    void 세운다() {
        WebtoonWorkRepository works = mock(WebtoonWorkRepository.class);
        when(works.findFirstByRunId(anyString())).thenAnswer(c -> rows.stream()
                .filter(w -> c.getArgument(0).equals(w.getRunId())).findFirst());
        when(works.save(any(WebtoonWork.class))).thenAnswer(c -> c.getArgument(0));

        BrowserLinkRepository links = mock(BrowserLinkRepository.class);
        when(links.existsByUserIdAndBrowserUid(anyLong(), anyString())).thenAnswer(c ->
                ME == (long) c.getArgument(0) && linkedUids.contains(c.getArgument(1)));

        ledger = new WorkLedger(works, links);
    }

    /** 이 브라우저가 만든 작품 하나. 계정은 안 붙어 있다(게스트로 만든 것). */
    private WebtoonWork 작품(String runId, String uid, boolean isPublic) {
        WebtoonWork w = WebtoonWork.moved(runId, runId, null, uid, Instant.now());
        w.setPublic(isPublic);
        rows.add(w);
        return w;
    }

    @Test
    @DisplayName("공개 작품은 로그인 안 해도 본다")
    void 공개는_누구나() {
        작품("run-공개", "uid-a", true);

        assertThat(ledger.mayRead("run-공개", null)).isTrue();
        assertThat(ledger.mayRead("run-공개", 남)).isTrue();
    }

    @Test
    @DisplayName("비공개 작품은 로그인 안 하면 못 본다 — 주소만 알면 열리던 구멍이 여기서 막힌다")
    void 비공개는_게스트가_못_본다() {
        작품("run-비공개", "uid-a", false);

        assertThat(ledger.mayRead("run-비공개", null)).isFalse();
    }

    @Test
    @DisplayName("남의 비공개 작품은 로그인해도 못 본다")
    void 비공개는_남이_못_본다() {
        작품("run-비공개", "uid-a", false);
        linkedUids.add("uid-a");                 // ME 에게만 이어져 있다

        assertThat(ledger.mayRead("run-비공개", 남)).isFalse();
    }

    @Test
    @DisplayName("내 비공개 작품은 내가 본다 — 계정이 직접 붙어 있을 때")
    void 계정이_붙어_있으면_본다() {
        WebtoonWork w = 작품("run-비공개", "uid-a", false);
        w.claimBy(ME);

        assertThat(ledger.mayRead("run-비공개", ME)).isTrue();
    }

    @Test
    @DisplayName("내 비공개 작품은 내가 본다 — 로그인 전에 만들어 브라우저로만 이어졌을 때")
    void 브라우저로_이어져_있으면_본다() {
        작품("run-비공개", "uid-a", false);
        linkedUids.add("uid-a");

        assertThat(ledger.mayRead("run-비공개", ME)).isTrue();
    }

    @Test
    @DisplayName("게스트끼리는 서로를 구별할 수 없으므로 주인이 될 수 없다")
    void 로그인_안_하면_주인이_아니다() {
        작품("run-비공개", "uid-a", false);
        linkedUids.add("uid-a");

        assertThat(ledger.mayRead("run-비공개", null)).isFalse();
    }

    @Test
    @DisplayName("아직 이 표로 안 옮긴 옛 작품은 막지 않는다 — 막으면 자기 작품을 못 본다")
    void 모르는_작품은_통과() {
        assertThat(ledger.mayRead("옮기지-않은-작품", null)).isTrue();
        assertThat(ledger.isPublic("옮기지-않은-작품")).isTrue();
    }

    @Test
    @DisplayName("공개 여부는 주인만 바꾼다. 모르는 작품은 아무도 못 바꾼다")
    void 바꾸는_것은_주인만() {
        작품("run-1", "uid-a", true);
        linkedUids.add("uid-a");

        assertThat(ledger.mayChange("run-1", ME)).isTrue();
        assertThat(ledger.mayChange("run-1", 남)).isFalse();
        assertThat(ledger.mayChange("run-1", null)).isFalse();
        // 읽기와 반대다: 모르는 작품을 아무나 바꾸게 두면 남의 작품을 내릴 수 있다.
        assertThat(ledger.mayChange("모르는-작품", ME)).isFalse();
    }

    @Test
    @DisplayName("공개 여부를 바꾸면 그대로 읽힌다. 같은 값이면 아무 일도 안 한다")
    void 바꾸기() {
        작품("run-1", "uid-a", true);

        assertThat(ledger.setPublic("run-1", false)).isTrue();
        assertThat(ledger.isPublic("run-1")).isFalse();
        assertThat(ledger.setPublic("run-1", false)).isFalse();   // 이미 그 값
    }

    @Test
    @DisplayName("자리 이름으로 공개/비공개를 가른다 — CloudFront 가 images/ 만 내준다")
    void 자리로_가른다() {
        assertThat(PrivateArt.isPrivate("images/webtoon/abc.jpg")).isFalse();
        assertThat(PrivateArt.isPrivate("private/webtoon/abc.jpg")).isTrue();

        assertThat(PrivateArt.moved("images/webtoon/abc.jpg", false))
                .isEqualTo("private/webtoon/abc.jpg");
        assertThat(PrivateArt.moved("private/webtoon/abc.jpg", true))
                .isEqualTo("images/webtoon/abc.jpg");
        // 이미 맞는 자리면 그대로 — 옮길 것이 없다
        assertThat(PrivateArt.moved("images/webtoon/abc.jpg", true))
                .isEqualTo("images/webtoon/abc.jpg");
        assertThat(Optional.ofNullable(PrivateArt.moved(null, true))).isEmpty();
    }
}
