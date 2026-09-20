package com.lore.webtoon.job;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 줄에서의 자리를 사람에게 적어 주는 자리.
 *
 * <b>여기서 지키는 것은 「적어 준 말이 사람을 두 번 속이지 않는가」 다.</b>
 * 기다리는 것 자체는 어쩔 수 없지만, 「약 5분」이라고 적어 놓고 40분이 걸리면
 * 그건 기다리게 한 것이 아니라 속인 것이다.
 */
class JobQueueTest {

    @Test
    @DisplayName("앞이 비면 아무 말도 안 한다 — 적을 것이 없다")
    void 앞이_비면_안_적는다() {
        assertThat(new JobQueue.Spot(0, 0).line()).isEmpty();
    }

    @Test
    @DisplayName("앞에 몇 명이고 몇 분인지 한 줄로 적는다")
    void 한_줄로_적는다() {
        assertThat(new JobQueue.Spot(3, 25 * 60).line())
                .isEqualTo("앞에 3명 · 약 25분 뒤 시작");
    }

    @Test
    @DisplayName("30초를 「0분」이라고 적지 않는다 — 바로 시작하는 줄 안다")
    void 영분은_없다() {
        assertThat(new JobQueue.Spot(1, 30).minutes()).isEqualTo(1);
        assertThat(new JobQueue.Spot(1, 1).minutes()).isEqualTo(1);
        assertThat(new JobQueue.Spot(1, 0).minutes()).isEqualTo(1);
    }

    @Test
    @DisplayName("남는 초는 올린다 — 8분 10초를 「8분」이라 적으면 매번 늦는다")
    void 올려서_적는다() {
        /* 내림하면 적어 준 시각이 늘 실제보다 이르다. 매번 조금씩 늦는 것은
           한 번 크게 늦는 것보다 신뢰를 더 깎는다. */
        assertThat(new JobQueue.Spot(1, 8 * 60 + 10).minutes()).isEqualTo(9);
        assertThat(new JobQueue.Spot(1, 8 * 60).minutes()).isEqualTo(8);
    }

    @Test
    @DisplayName("여러 명이면 시간이 쌓인다")
    void 시간이_쌓인다() {
        // 파도 셋이면 8.1분 × 3 ≈ 25분
        long three = 3L * (8 * 60 + 6);
        assertThat(new JobQueue.Spot(3, three).minutes()).isEqualTo(25);
    }
}
