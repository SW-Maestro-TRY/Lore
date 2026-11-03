package com.lore.webtoon.play;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** 운영 세계 예시 인물 → 캐릭터. id 는 다시 떠도 같고, 설명은 나이 · 설정 · 관계를 잇는다. */
class PlayPeopleTest {

    @Test
    void 설명은_나이_설정_관계를_잇는다() {
        assertThat(PlayPeople.description(Map.of("age", 28, "desc", "전직 소방관.", "relation", "가족을 숨기고 있다.")))
                .isEqualTo("28세. 전직 소방관. 가족을 숨기고 있다.");
        assertThat(PlayPeople.description(Map.of("desc", "겁이 많다."))).isEqualTo("겁이 많다.");
    }

    @Test
    void id_는_세계_사건_이름으로_정해지고_스무_자다() {
        String a = PlayPeople.idOf("zombie", "hidden-family", "이현");
        assertThat(a).hasSize(20).startsWith("pp").isEqualTo(PlayPeople.idOf("zombie", "hidden-family", "이현"));
        assertThat(a).isNotEqualTo(PlayPeople.idOf("zombie", "virus-maker", "이현"));
    }
}
