package com.lore.trailer.foreshadowing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 회수 칸 가리기 — 독자가 읽은 회차 N 뒤에 회수된 복선은 미회수로 보인다.
 *
 * <p>★ 여기서 지키는 것은 <b>독자가 아직 읽지 않은 결말</b>이다. 가리기가 틀려도 예외도 로그도 없다 —
 * 카드 상세를 열어 본 독자만 안다. 그래서 경계(회수 회차 = N)까지 못 박는다.
 *
 * <p>엔티티는 SQL 로만 들어와서 만드는 팩토리가 없다. 시험은 리플렉션으로 칸을 채운다(lore 통합 검사와 같은 방식).
 */
@DisplayName("복선 카드 — 회수 칸을 독자의 회차로 가린다")
class ForeshadowingTest {

    /** T434 꼴 — 110화에 심고 214화에 회수. */
    private static Foreshadowing resolvedCard() {
        return card(110, Foreshadowing.RESOLVED, 214, "214화에서 결말이 난다");
    }

    private static Foreshadowing card(int startChapter, String status, Integer resolvedChapter, String resolution) {
        Foreshadowing f = new Foreshadowing();
        ReflectionTestUtils.setField(f, "threadId", "T434");
        ReflectionTestUtils.setField(f, "startChapter", startChapter);
        ReflectionTestUtils.setField(f, "status", status);
        ReflectionTestUtils.setField(f, "resolvedChapter", resolvedChapter);
        ReflectionTestUtils.setField(f, "resolution", resolution);
        return f;
    }

    @Test
    @DisplayName("★ 회수 회차보다 앞을 읽은 독자에게는 미회수 — 셋이 함께 가려진다")
    void hiddenBeforeResolution() {
        Foreshadowing f = resolvedCard();

        assertThat(f.statusAt(200)).isEqualTo("open");
        assertThat(f.resolvedChapterAt(200)).isNull();
        assertThat(f.resolutionAt(200)).isNull();
    }

    @Test
    @DisplayName("회수 회차를 읽은 독자부터 보인다 — 경계는 N = 회수 회차")
    void visibleFromTheResolvingChapter() {
        Foreshadowing f = resolvedCard();

        assertThat(f.statusAt(213)).isEqualTo("open");
        assertThat(f.statusAt(214)).isEqualTo("resolved");
        assertThat(f.resolvedChapterAt(214)).isEqualTo(214);
        assertThat(f.resolutionAt(214)).isEqualTo("214화에서 결말이 난다");
        assertThat(f.statusAt(400)).isEqualTo("resolved");
    }

    @Test
    @DisplayName("미회수 복선은 어느 회차에서도 그대로 — 가릴 것이 없다")
    void openStaysOpen() {
        Foreshadowing f = card(1, Foreshadowing.OPEN, null, null);

        assertThat(f.statusAt(1)).isEqualTo("open");
        assertThat(f.statusAt(400)).isEqualTo("open");
        assertThat(f.resolvedChapterAt(400)).isNull();
        assertThat(f.resolutionAt(400)).isNull();
    }

    @Test
    @DisplayName("심은 회차가 N 이하여야 보인다 — 경계는 N = 심은 회차")
    void plantedBy() {
        Foreshadowing f = resolvedCard();

        assertThat(f.isPlantedBy(109)).isFalse();
        assertThat(f.isPlantedBy(110)).isTrue();
        assertThat(f.isPlantedBy(400)).isTrue();
    }

    @Test
    @DisplayName("인물 칸 — 줄바꿈으로 이은 글을 목록으로, 빈 글은 빈 목록으로")
    void people() {
        assertThat(Foreshadowing.splitPeople("")).isEmpty();
        assertThat(Foreshadowing.splitPeople(null)).isEmpty();
        assertThat(Foreshadowing.splitPeople("Shanks")).containsExactly("Shanks");
        assertThat(Foreshadowing.splitPeople("Shanks\nMonkey D. Luffy")).containsExactly("Shanks", "Monkey D. Luffy");
        // 쉼표가 든 이름이 하나 있다 — 쉼표로 나누지 않는다.
        assertThat(Foreshadowing.splitPeople("Iceburg, Mayor of Water 7\nFranky"))
                .containsExactly("Iceburg, Mayor of Water 7", "Franky");
    }
}
