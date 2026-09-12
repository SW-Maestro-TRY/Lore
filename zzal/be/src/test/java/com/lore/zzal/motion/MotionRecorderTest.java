package com.lore.zzal.motion;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 맥미니 재생성 한도 — <b>딱 그 값과 하나 넘는 값</b>(M-6).
 *
 * <h3>★ 왜 이 시험이 필요한가</h3>
 * {@code MotionServiceTest} 는 {@link MotionRecorder} 를 목으로 두고 <b>반환값만</b> 스텁한다.
 * 그래서 "몇 번째 재생성까지 허락하나" 라는 실제 상태 전이가 한 번도 안 돈다. 경계가 하나 어긋나면
 * 세 번째 재생성이 돌거나(맥미니 시간이 한 판에 N배로 나간다), 반대로 두 번째가 막혀
 * 일곱 판을 다 못 보고 보류함으로 내려간다. <b>보류는 자동으로 안 풀리므로 그 동작은 영영 멈춘다.</b>
 *
 * ★ 여기서는 저장소만 목이고 {@link ZzalMotion} 은 진짜다 — 막는 규칙이 엔티티 안에 있기 때문이다.
 */
@DisplayName("맥미니 재생성 — 한도 2의 경계")
class MotionRecorderTest {

    private static final Instant NOW = Instant.parse("2026-09-11T14:00:00Z");
    private static final long MOTION_ID = 42L;
    private static final int MAX = 2;

    private final MotionCatalog catalog = new MotionCatalog("", "", "v1");
    private ZzalMotionRepository repository;
    private MotionRecorder recorder;

    @BeforeEach
    void setUp() {
        repository = mock(ZzalMotionRepository.class);
        recorder = new MotionRecorder(repository, mock(ZzalMotionCandidateRepository.class));
    }

    /** {@code regenRound} 가 이미 그만큼 돈 모션 한 줄. */
    private ZzalMotion motionAtRound(int round) {
        ZzalMotion m = ZzalMotion.forCatalog(7L, catalog.bySeq(7).orElseThrow(), NOW);
        ReflectionTestUtils.setField(m, "id", MOTION_ID);
        ReflectionTestUtils.setField(m, "status", MotionStatus.BAKING);
        ReflectionTestUtils.setField(m, "regenRound", round);
        when(repository.findById(MOTION_ID)).thenReturn(Optional.of(m));
        return m;
    }

    @Test
    @DisplayName("★ 0 라운드면 맥미니에 넘기고 라운드가 1 이 된다")
    void roundZeroGoesToTheLocalRunner() {
        ZzalMotion m = motionAtRound(0);

        assertThat(recorder.requestLocalRegen(MOTION_ID, MAX)).isTrue();

        assertThat(m.getStatus()).isEqualTo(MotionStatus.LOCAL_REQUESTED);
        assertThat(m.getRegenRound()).isEqualTo(1);
    }

    @Test
    @DisplayName("★ 한도 직전(1)까지는 넘긴다 — 여기가 막히면 일곱 판을 다 못 보고 보류로 내려간다")
    void lastAllowedRoundStillGoes() {
        ZzalMotion m = motionAtRound(MAX - 1);

        assertThat(recorder.requestLocalRegen(MOTION_ID, MAX)).isTrue();

        assertThat(m.getStatus()).isEqualTo(MotionStatus.LOCAL_REQUESTED);
        assertThat(m.getRegenRound()).isEqualTo(MAX);
    }

    @Test
    @DisplayName("★★ 정확히 한도(2)에 닿으면 거절하고 보류함으로 — 라운드는 더 안 오른다")
    void exactlyAtTheLimitHolds() {
        ZzalMotion m = motionAtRound(MAX);

        assertThat(recorder.requestLocalRegen(MOTION_ID, MAX)).isFalse();

        assertThat(m.getStatus()).isEqualTo(MotionStatus.HOLD);
        assertThat(m.getRegenRound()).as("거절된 요청은 라운드를 쓰지 않는다").isEqualTo(MAX);
    }

    @Test
    @DisplayName("★ 한도를 넘긴 값(3, 오염된 데이터)도 보류다 — 부등호가 == 이면 여기가 새어 세 번째가 돈다")
    void beyondTheLimitAlsoHolds() {
        ZzalMotion m = motionAtRound(MAX + 1);

        assertThat(recorder.requestLocalRegen(MOTION_ID, MAX)).isFalse();

        assertThat(m.getStatus()).isEqualTo(MotionStatus.HOLD);
        assertThat(m.getRegenRound()).isEqualTo(MAX + 1);
    }

    @Test
    @DisplayName("★ 거절 뒤 같은 호출을 다시 해도 같은 답 — 재시도로 한도를 넘길 길이 없다")
    void retryingAfterRefusalChangesNothing() {
        ZzalMotion m = motionAtRound(MAX);

        assertThat(recorder.requestLocalRegen(MOTION_ID, MAX)).isFalse();
        assertThat(recorder.requestLocalRegen(MOTION_ID, MAX)).isFalse();
        assertThat(recorder.requestLocalRegen(MOTION_ID, MAX)).isFalse();

        assertThat(m.getStatus()).isEqualTo(MotionStatus.HOLD);
        assertThat(m.getRegenRound()).isEqualTo(MAX);
    }

    @Test
    @DisplayName("없는 모션이면 조용히 false — 예외로 굽는 스레드를 끊지 않는다")
    void missingMotionIsRefusedQuietly() {
        when(repository.findById(anyLong())).thenReturn(Optional.empty());

        assertThat(recorder.requestLocalRegen(MOTION_ID, MAX)).isFalse();
    }
}
