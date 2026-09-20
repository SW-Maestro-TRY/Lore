package com.lore.zzal.dev;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 개발용 시계 — <b>입력 경계와 넘침</b>(M-31).
 *
 * <h3>★ 왜 운영에 안 나가는 코드를 시험하나</h3>
 * 이 도구는 꺼져 있어 사용자 피해가 없다. 대신 <b>시연·검증이 전부 이것 위에서 돈다</b> —
 * "19:00 에 맞춰 봤더니 되더라" 의 근거가 이 계산이다. 여기가 조용히 틀리면 그 위에서 확인한
 * 규칙 판정이 전부 거짓이 되고, 우리는 틀린 것을 맞다고 믿은 채 배포한다.
 *
 * <h3>★ 넘침을 예외로 터뜨리는 이유</h3>
 * {@code minutes = Long.MAX_VALUE} 를 곱하면 조용히 음수로 감긴다. 그러면 컨트롤러의 30일 상한을
 * <b>우회</b>해 버린다 — 상한이 있는데 통과하는 모양이라 아무도 모른다.
 */
@DisplayName("개발용 시계 — 입력 경계")
class DevRequestsTest {

    /** 컨트롤러의 상한과 같은 값(DevClockController.MAX_ADVANCE). */
    private static final Duration MAX_ADVANCE = Duration.ofDays(30);

    @Nested
    @DisplayName("얼마나 당길 것인가")
    class Advance {

        private Duration by(Long seconds, Long minutes) {
            return new DevRequests.AdvanceClock(seconds, minutes).toDuration();
        }

        @Test
        @DisplayName("초·분 아무 쪽이나, 둘 다 주면 더한다")
        void secondsAndMinutesAddUp() {
            assertThat(by(90L, null)).isEqualTo(Duration.ofSeconds(90));
            assertThat(by(null, 4L)).isEqualTo(Duration.ofMinutes(4));
            assertThat(by(30L, 4L)).isEqualTo(Duration.ofSeconds(270));
        }

        @Test
        @DisplayName("둘 다 안 주면 0 — 컨트롤러가 이 값을 보고 400 으로 거절한다")
        void nothingGivenIsZero() {
            assertThat(by(null, null)).isZero();
            assertThat(by(0L, 0L)).isZero();
        }

        @Test
        @DisplayName("★ 음수는 음수 그대로 나온다 — 0 으로 뭉개면 컨트롤러가 거절할 근거를 잃는다")
        void negativeStaysNegative() {
            assertThat(by(-1L, null)).isNegative();
            assertThat(by(null, -5L)).isEqualTo(Duration.ofMinutes(-5));
            assertThat(by(60L, -1L)).as("합이 정확히 0 이면 그것도 거절 대상이다").isZero();
        }

        @Test
        @DisplayName("★ 정확히 30일은 상한 안, 30일 + 1초는 상한 밖")
        void theThirtyDayEdge() {
            Duration exactly = by(null, MAX_ADVANCE.toMinutes());
            Duration oneMore = by(1L, MAX_ADVANCE.toMinutes());

            assertThat(exactly).isEqualTo(MAX_ADVANCE);
            assertThat(exactly.compareTo(MAX_ADVANCE)).as("상한 안 — 거절되면 안 된다").isNotPositive();
            assertThat(oneMore.compareTo(MAX_ADVANCE)).as("상한 밖 — 거절돼야 한다").isPositive();
        }

        @Test
        @DisplayName("★★ 넘치면 예외 — 조용히 음수로 감기면 30일 상한을 그냥 지나간다")
        void overflowThrowsInsteadOfWrapping() {
            assertThatThrownBy(() -> by(null, Long.MAX_VALUE))
                    .isInstanceOf(ArithmeticException.class);
            assertThatThrownBy(() -> by(Long.MAX_VALUE, 1L))
                    .isInstanceOf(ArithmeticException.class);
            assertThatThrownBy(() -> by(Long.MIN_VALUE, -1L))
                    .isInstanceOf(ArithmeticException.class);
        }
    }

    @Nested
    @DisplayName("시계 맞추기 — 셋 중 하나만")
    class SetClock {

        private int given(Instant at, Integer sinceHatch, String localTime) {
            return new DevRequests.SetClock(at, sinceHatch, localTime).given();
        }

        @Test
        @DisplayName("★ 0개 · 2개 · 3개는 컨트롤러가 거절할 값 — given() 이 1 일 때만 통과한다")
        void onlyExactlyOneIsAccepted() {
            Instant at = Instant.parse("2026-09-05T10:00:00Z");

            assertThat(given(null, null, null)).isZero();
            assertThat(given(at, null, null)).isEqualTo(1);
            assertThat(given(null, 40, null)).isEqualTo(1);
            assertThat(given(null, null, "19:00")).isEqualTo(1);
            assertThat(given(at, 40, null)).isEqualTo(2);
            assertThat(given(at, null, "19:00")).isEqualTo(2);
            assertThat(given(null, 40, "19:00")).isEqualTo(2);
            assertThat(given(at, 40, "19:00")).isEqualTo(3);
        }

        @Test
        @DisplayName("0 분·0 시각도 '준 것' 으로 센다 — null 만 안 준 것이다")
        void zeroCountsAsGiven() {
            assertThat(given(Instant.EPOCH, null, null)).isEqualTo(1);
            assertThat(given(null, 0, null)).isEqualTo(1);
            assertThat(given(null, null, "00:00")).isEqualTo(1);
        }
    }
}
