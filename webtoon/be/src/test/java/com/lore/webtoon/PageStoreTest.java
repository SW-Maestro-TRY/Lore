package com.lore.webtoon;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 작품 그림이 S3 어디에 있나 — 적고 찾는 규칙.
 *
 * 여기서 지키는 것은 둘이다. <b>다시 구운 그림이 옛 주소를 안 남기는가</b>,
 * 그리고 <b>안 올라간 작품에 대해 거짓 주소를 안 지어내는가</b>.
 */
class PageStoreTest {

    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-09-06T00:00:00Z"), ZoneOffset.UTC);
    private static final String CDN = "https://lorecomic.com";

    private final List<WebtoonPage> rows = new ArrayList<>();
    private PageStore store;

    @BeforeEach
    void 저장소를_세운다() {
        WebtoonPageRepository repo = mock(WebtoonPageRepository.class);
        when(repo.findByRunIdAndPageNoAndWidth(anyString(), anyInt(), anyInt()))
                .thenAnswer(c -> rows.stream()
                        .filter(p -> p.getRunId().equals(c.getArgument(0))
                                && p.getPageNo() == (int) c.getArgument(1)
                                && p.getWidth() == (int) c.getArgument(2))
                        .findFirst());
        when(repo.findByRunIdOrderByPageNoAscWidthAsc(anyString()))
                .thenAnswer(c -> rows.stream()
                        .filter(p -> p.getRunId().equals(c.getArgument(0)))
                        .sorted((a, b) -> a.getPageNo() != b.getPageNo()
                                ? Integer.compare(a.getPageNo(), b.getPageNo())
                                : Integer.compare(a.getWidth(), b.getWidth()))
                        .toList());
        when(repo.existsByRunId(anyString())).thenAnswer(c -> rows.stream()
                .anyMatch(p -> p.getRunId().equals(c.getArgument(0))));
        when(repo.save(any(WebtoonPage.class))).thenAnswer(c -> {
            WebtoonPage p = c.getArgument(0);
            if (!rows.contains(p)) {
                rows.add(p);
            }
            return p;
        });
        store = new PageStore(repo, CDN, FIXED);
    }

    private static PageStore.Upload up(int page, int width, String key) {
        return new PageStore.Upload(page, width, key, 1234);
    }

    @Test
    @DisplayName("올린 것을 적고 주소로 돌려준다")
    void 적고_찾는다() {
        assertThat(store.record("run-1", List.of(up(2, 1080, "images/webtoon/abc.jpg")))).isEqualTo(1);

        assertThat(store.urlOf("run-1", 2, 1080))
                .isEqualTo("https://lorecomic.com/images/webtoon/abc.jpg");
    }

    @Test
    @DisplayName("다시 구워 올리면 주소가 바뀐다 — 옛 주소를 들고 있으면 지운 말풍선이 계속 보인다")
    void 다시_구우면_주소가_바뀐다() {
        store.record("run-1", List.of(up(2, 1080, "images/webtoon/old.jpg")));
        assertThat(store.record("run-1", List.of(up(2, 1080, "images/webtoon/new.jpg")))).isZero();

        assertThat(rows).hasSize(1);            // 줄이 늘지 않는다
        assertThat(store.urlOf("run-1", 2, 1080)).endsWith("/new.jpg");
    }

    @Test
    @DisplayName("안 올라간 것은 주소를 안 지어낸다 — 그때는 하네스가 내보내야 한다")
    void 없으면_null() {
        store.record("run-1", List.of(up(2, 1080, "images/webtoon/abc.jpg")));

        assertThat(store.urlOf("run-1", 2, 320)).isNull();     // 폭이 다르다
        assertThat(store.urlOf("run-1", 3, 1080)).isNull();    // 장이 다르다
        assertThat(store.urlOf("딴-작품", 2, 1080)).isNull();
    }

    @Test
    @DisplayName("장·폭이 다르면 따로 적힌다. 원본(0)도 한 줄이다")
    void 장과_폭마다_한_줄() {
        store.record("run-1", List.of(
                up(2, 0, "images/webtoon/o.png"),
                up(2, 320, "images/webtoon/s.jpg"),
                up(2, 1080, "images/webtoon/m.jpg"),
                up(3, 1080, "images/webtoon/n.jpg")));

        assertThat(rows).hasSize(4);
        assertThat(store.urlsOf("run-1").get(2)).containsOnlyKeys(0, 320, 1080);
        assertThat(store.urlsOf("run-1").get(3)).containsOnlyKeys(1080);
    }

    @Test
    @DisplayName("키가 빈 줄은 안 적는다 — 열 수 없는 주소를 들고 있으면 안 된다")
    void 빈_키는_버린다() {
        assertThat(store.record("run-1", List.of(
                up(2, 1080, null), up(3, 1080, "  ")))).isZero();
        assertThat(rows).isEmpty();
    }

    @Test
    @DisplayName("작품 번호가 없으면 아무 일도 안 한다")
    void 작품_번호가_없으면() {
        assertThat(store.record(null, List.of(up(2, 1080, "k")))).isZero();
        assertThat(store.record("  ", List.of(up(2, 1080, "k")))).isZero();
        assertThat(rows).isEmpty();
    }

    @Test
    @DisplayName("읽어 주는 곳을 안 정했으면 같은 도메인의 상대경로로 준다")
    void CDN_이_비면_상대경로() {
        WebtoonPageRepository repo = mock(WebtoonPageRepository.class);
        when(repo.findByRunIdAndPageNoAndWidth(anyString(), anyInt(), anyInt()))
                .thenReturn(Optional.of(WebtoonPage.of(
                        "run-1", 2, 1080, "images/webtoon/abc.jpg", 1, Instant.now())));

        assertThat(new PageStore(repo, "", FIXED).urlOf("run-1", 2, 1080))
                .isEqualTo("/images/webtoon/abc.jpg");
    }

    @Test
    @DisplayName("읽어 주는 곳 끝의 빗금은 하나로 — 두 겹이 되면 주소가 깨진다")
    void 빗금_정리() {
        WebtoonPageRepository repo = mock(WebtoonPageRepository.class);
        when(repo.findByRunIdAndPageNoAndWidth(anyString(), anyInt(), anyInt()))
                .thenReturn(Optional.of(WebtoonPage.of(
                        "run-1", 2, 1080, "images/webtoon/abc.jpg", 1, Instant.now())));

        assertThat(new PageStore(repo, "https://cdn.example.com///", FIXED)
                .urlOf("run-1", 2, 1080))
                .isEqualTo("https://cdn.example.com/images/webtoon/abc.jpg");
    }

    @Test
    @DisplayName("올라와 있는 작품인지 물을 수 있다")
    void 올라왔나() {
        assertThat(store.has("run-1")).isFalse();
        store.record("run-1", List.of(up(2, 1080, "images/webtoon/abc.jpg")));
        assertThat(store.has("run-1")).isTrue();
    }
}
