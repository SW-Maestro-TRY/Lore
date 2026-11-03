package com.lore.webtoon.play;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 운영 세계 사건의 상황 — 장소와 사건마다 고른 인물의 역할 · 설정이 붙고, 주인공은 다시 안 적는다. */
class PlayCastSituationTest {

    private final Map<String, Object> world = Map.of("cast", List.of(
            Map.of("name", "카일", "role", "교수", "desc", "말이 짧다"),
            Map.of("name", "리안", "role", "선배", "desc", "웃는다")));

    @Test
    void 고른_인물만_역할_설정과_함께_붙는다() {
        String s = PlayController.catalogSituation(world,
                Map.of("situation", "주인공이 입학한다.", "place", "대강당", "cast", List.of("카일")), "루나");
        assertThat(s).startsWith("주인공이 입학한다.").contains("장소: 대강당")
                .contains("이 사건에 나오는 인물:\n- 카일 (교수): 말이 짧다").doesNotContain("리안");
    }

    @Test
    void 인물을_안_고른_사건과_주인공은_그대로() {
        assertThat(PlayController.catalogSituation(world, Map.of("situation", "상황"), "루나")).isEqualTo("상황");
        assertThat(PlayController.catalogSituation(world, Map.of("situation", "상황", "cast", List.of("카일")), "카일"))
                .isEqualTo("상황");
    }
}
