package com.lore.zzal.pet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static com.lore.zzal.pet.AwakeClockTest.kst;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 아기 시간표 — 전부 서버 카운터에서 파생(정본 12장·플랜 T2 결정 2).
 *
 * ★ 여기서 지키는 것은 "나갔다 와도 밀린 부름이 순서대로" 다. 브라우저에 위치를 저장하면
 *   새로고침·기기 변경에서 어긋나는데, 그건 사용자가 두 기기를 써 봐야만 드러난다.
 */
@DisplayName("아기 시간표 — 카운터에서 파생")
class TutorialScheduleTest {

    private static final Instant T0 = kst("2026-09-05 12:00");

    private static ZzalPet baby() {
        ZzalPet pet = ZzalPet.hatch(1L, "여울", null, "k", T0);
        pet.markAlive("s", "i", T0);
        return pet;
    }

    @Test
    @DisplayName("부화 직후 — 0분 '밥' 만 도래, 그것이 current")
    void atHatch() {
        TutorialSchedule.State s = TutorialSchedule.of(baby(), T0);
        assertThat(s.active()).isTrue();
        assertThat(s.minutesSince()).isZero();
        assertThat(s.steps()).hasSize(9);
        assertThat(s.steps().get(0).key()).isEqualTo(TutorialSchedule.Step.FEED);
        assertThat(s.steps().get(0).current()).isTrue();
        assertThat(s.steps().get(1).key()).isEqualTo(TutorialSchedule.Step.PET);
        assertThat(s.steps().get(1).dueAt()).isEqualTo(T0.plus(Duration.ofMinutes(3)));
        assertThat(s.steps().get(1).current()).isFalse();
    }

    @Test
    @DisplayName("밥을 주면 FEED 가 done, 3분 뒤에야 PET 가 current")
    void feedThenPet() {
        ZzalPet pet = baby();
        pet.feed(T0);
        TutorialSchedule.State s = TutorialSchedule.of(pet, T0.plus(Duration.ofMinutes(1)));
        assertThat(s.steps().get(0).done()).isTrue();
        assertThat(s.steps().stream().filter(TutorialSchedule.StepState::current)).isEmpty();

        s = TutorialSchedule.of(pet, T0.plus(Duration.ofMinutes(3)));
        assertThat(s.steps().get(1).current()).isTrue();
    }

    @Test
    @DisplayName("★ 30분 방치 뒤 돌아와도 밀린 부름이 순서대로 — current 는 가장 앞의 안 한 것 하나")
    void resumesInOrder() {
        ZzalPet pet = baby();
        TutorialSchedule.State s = TutorialSchedule.of(pet, T0.plus(Duration.ofMinutes(30)));
        long due = s.steps().stream().filter(x -> !x.dueAt().isAfter(T0.plus(Duration.ofMinutes(30)))).count();
        assertThat(due).isEqualTo(7);                                             // 0·3·8·12·15·20·25 (40 은 아직)
        assertThat(s.steps().stream().filter(TutorialSchedule.StepState::current)
                .map(TutorialSchedule.StepState::key)).containsExactly(TutorialSchedule.Step.FEED);
    }

    @Test
    @DisplayName("60분이 지나면 active=false 이고 DONE 은 done. 남은 부름은 그대로 남는다(해석 9)")
    void afterSixtyMinutes() {
        ZzalPet pet = baby();
        pet.feed(T0);
        TutorialSchedule.State s = TutorialSchedule.of(pet, T0.plus(Duration.ofMinutes(61)));
        assertThat(s).isNotNull();
        assertThat(s.active()).isFalse();
        assertThat(s.steps().get(8).key()).isEqualTo(TutorialSchedule.Step.DONE);
        assertThat(s.steps().get(8).done()).isTrue();
        assertThat(s.steps().get(1).current()).isTrue();                          // PET 이 다음 부름
    }

    @Test
    @DisplayName("성격·낮잠·공유도 카운터로 판정")
    void countersDrivenDone() {
        ZzalPet pet = baby();
        pet.choosePersonality(Personality.SHY, null);
        pet.share();
        pet.sleep(T0.plus(Duration.ofMinutes(40)));
        pet.settle(T0.plus(Duration.ofMinutes(51)));                              // 낮잠 자동 기상
        TutorialSchedule.State s = TutorialSchedule.of(pet, T0.plus(Duration.ofMinutes(51)));
        assertThat(s.steps().get(3).done()).isTrue();                             // PERSONALITY
        assertThat(s.steps().get(6).done()).isTrue();                             // SHARE
        assertThat(s.steps().get(7).done()).isTrue();                             // NAP
    }
}
