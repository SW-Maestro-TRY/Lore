package com.lore.zzal.pet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 결정적 뽑기.
 *
 * ★ 여기서 지키는 것은 <b>"같은 상황이면 같은 답"</b>이다. 이게 깨지면 정산이 부를 때마다 다른 답을 내고,
 *   그러면 "서버가 꺼져 있었어도 결과가 같다"(B4)는 시계 엔진의 뿌리가 통째로 흔들린다.
 *   그리고 그 어긋남은 <b>가끔 실패하는 테스트</b>로만 드러나서 원인을 찾기가 매우 어렵다.
 */
@DisplayName("뽑기 — 결정적이되 예측하기 어렵게")
class ChanceTest {

    @Test
    @DisplayName("★★ 같은 입력이면 언제나 같은 답 — 몇 번을 불러도")
    void deterministic() {
        for (int i = 0; i < 100; i++) {
            assertThat(Chance.percent("sick-neglect", 1_700_000_000L, 3))
                    .isEqualTo(Chance.percent("sick-neglect", 1_700_000_000L, 3));
        }
        assertThat(Chance.pick(1000, "sick-natural", 42L, 0))
                .isEqualTo(Chance.pick(1000, "sick-natural", 42L, 0));
    }

    @Test
    @DisplayName("★ 용도(salt)가 다르면 같은 씨앗이라도 다른 값 — 병 판정과 발병 시각이 같이 움직이면 안 된다")
    void saltSeparatesUses() {
        long seed = 1_700_000_000L;
        int differ = 0;
        for (int i = 0; i < 50; i++) {
            if (Chance.percent("sick-neglect", seed, i) != Chance.percent("sick-natural", seed, i)) {
                differ++;
            }
        }
        assertThat(differ).isGreaterThan(40);
    }

    @Test
    @DisplayName("★ 규칙적으로 오르는 입력(1·3·5…)에도 한쪽으로 안 쏠린다 — 30%가 실제로 30% 언저리")
    void oddCountersAreNotBiased() {
        int hits = 0;
        int trials = 0;
        for (long seed = 0; seed < 200; seed++) {
            for (int miss = 1; miss <= 9; miss += 2) {          // 케어 미스 홀수만
                trials++;
                if (Chance.hit(0.30, "sick-neglect", seed, miss)) {
                    hits++;
                }
            }
        }
        assertThat((double) hits / trials).isBetween(0.24, 0.36);
    }

    @Test
    @DisplayName("경계 — 0%는 절대, 100%는 언제나. bound 1 이하는 0")
    void bounds() {
        for (long seed = 0; seed < 50; seed++) {
            assertThat(Chance.hit(0.0, "x", seed)).isFalse();
            assertThat(Chance.hit(1.0, "x", seed)).isTrue();
            assertThat(Chance.percent("x", seed)).isBetween(0, 99);
            assertThat(Chance.pick(10, "x", seed)).isBetween(0L, 9L);
        }
        assertThat(Chance.pick(1, "x", 7L)).isZero();
        assertThat(Chance.pick(0, "x", 7L)).isZero();
    }
}
