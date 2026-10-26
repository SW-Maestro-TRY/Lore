package com.lore.webtoon.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 대기 줄 굴리기(#836). 기대값은 같은 상수로 돌린 {@code webtoon/tools/loadtest/sim.py} 의 값이다
 * (이야기 75 · 시트 45 · 장면 30 · 장 70 × 9 · 검수·마무리 50, 일꾼 3 · 그림 자리 4).
 */
class JobLineSimTest {

    private static JobLineSim.Item fresh(long id) {
        return new JobLineSim.Item(id, JobLineSim.Phase.STORY, false, 1, 0, 1.0, JobEta.SHEET);
    }

    @Test
    @DisplayName("혼자면 걸음 시간을 더한 만큼이다")
    void 혼자() {
        Map<Long, JobLineSim.When> got = JobLineSim.run(List.of(fresh(1)), 3, 4);
        assertThat(got.get(1L).finish()).isEqualTo(366);
        assertThat(got.get(1L).start()).isZero();
    }

    @Test
    @DisplayName("12명이 한꺼번에 누르면 시뮬레이터와 같은 때에 끝난다 — 걸음마다 줄 끝에 다시 서는 몫까지")
    void 열두_명() {
        List<JobLineSim.Item> line = new ArrayList<>();
        for (long i = 0; i < 12; i++) {
            line.add(fresh(i));
        }
        Map<Long, JobLineSim.When> got = JobLineSim.run(line, 3, 4);
        long[] expected = {684, 824, 964, 1104, 1314, 1454, 1594, 1734, 1944, 2084, 2224, 2364};
        for (int i = 0; i < 12; i++) {
            assertThat(got.get((long) i).finish()).as("%d번째", i).isEqualTo(expected[i]);
        }
        // 첫 걸음은 일꾼 셋씩 75초 간격으로 시작한다
        assertThat(got.get(11L).start()).isEqualTo(225);
    }

    @Test
    @DisplayName("도는 중인 편은 남은 몫부터 이어 간다 — 그리는 중이면 남은 장만")
    void 도는_중() {
        var drawing = new JobLineSim.Item(1, JobLineSim.Phase.PAGES, true, 1, 2, 1.0, 0);
        var waiting = fresh(2);
        Map<Long, JobLineSim.When> got = JobLineSim.run(List.of(drawing, waiting), 1, 4);
        // 남은 두 장(70초, 자리 넷이라 한 번에) + 검수·마무리 50
        assertThat(got.get(1L).finish()).isEqualTo(120);
        // 일꾼이 하나라 뒤 편은 앞 편이 놓은 뒤에야 시작한다
        assertThat(got.get(2L).start()).isEqualTo(120);
    }
}
