package com.lore.zzal.generation;

import com.lore.zzal.PetFixture;
import com.lore.zzal.pet.PetPhase;
import com.lore.zzal.pet.ZzalPet;
import com.lore.zzal.pet.ZzalPetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 멈춘 알 복구 — <b>이름을 받기 전(DRAFT)에도 굽는다</b>는 변화를 따라간 것(1.9).
 *
 * <h3>★ 무엇이 바뀌어서 이 시험이 생겼나</h3>
 * 전에는 이름이 들어와야 굽기 시작해서, 굽는 중 = {@code HATCHING} 하나였다. 지금은
 * <b>그림을 올리는 순간부터</b> 굽는다. 그래서 <b>{@code DRAFT} 인 채로 굽는 구간</b>이 새로 생겼고,
 * 그 사이 서버가 죽으면 그 굽기가 통째로 사라진다. 사용자는 이름을 낸 뒤
 * <b>다음 재기동까지 끝나지 않는 알</b>을 보게 된다.
 *
 * <h3>★ 그렇다고 DRAFT 를 전부 집으면 돈이 샌다</h3>
 * 두 가지를 걸러야 한다.
 * <ul>
 *   <li><b>한 번도 안 구운 초안</b> — 주인이 부르지도 않은 굽기에 돈이 나간다</li>
 *   <li><b>굽기가 이미 끝난 초안</b> — 멈춘 것이 아니라 이름을 기다리는 중이다. 다시 구우면 $0.25 가 두 번 나간다</li>
 * </ul>
 */
@DisplayName("멈춘 알 복구 — DRAFT 도 집되, 돈이 새는 둘은 거른다")
class StuckHatchRecoveryTest {

    private static final String V = "v2";
    private static final Instant LONG_AGO = Instant.parse("2026-09-11T00:00:00Z");

    private ZzalPetRepository pets;
    private GenJobRepository jobs;
    private HatchService hatch;
    private GenerationRecorder recorder;
    private StuckHatchRecovery recovery;

    @BeforeEach
    void setUp() {
        pets = mock(ZzalPetRepository.class);
        jobs = mock(GenJobRepository.class);
        hatch = mock(HatchService.class);
        recorder = mock(GenerationRecorder.class);
        recovery = new StuckHatchRecovery(pets, jobs, hatch, recorder, 2, 12);

        when(hatch.currentVersion()).thenReturn(V);
        when(hatch.stepsTotal(anyString())).thenReturn(5);
        when(jobs.findFirstByPetIdOrderByIdDesc(anyLong())).thenReturn(Optional.empty());
        when(jobs.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    /**
     * 오래전에 굽기 시작해 멈춘 펫 하나만 있는 상태로 만든다.
     *
     * ★ 아이디로 짝을 맞추지 않는다 — 이 시험들은 펫이 하나뿐이라 짝맞춤이 필요 없고,
     *   저장 전 펫은 아이디가 없어서 짝을 맞추려 들면 목이 조용히 안 걸린다.
     */
    private void stuck(ZzalPet pet, long attempts, int stepsDone) {
        when(pets.findByPhaseInAndHatchStartedAtBefore(any(Collection.class), any()))
                .thenReturn(List.of(pet));
        when(jobs.countByPetIdAndKind(any(), eq(GenKind.HATCH))).thenReturn(attempts);
        when(hatch.stepsDone(any(), anyString())).thenReturn(stepsDone);
    }

    /** 그림만 올린 초안 — 이름이 아직 없다. */
    private ZzalPet draft() {
        return ZzalPet.draft(1L, "images/zzal/src", LONG_AGO);
    }

    /** 이름이 들어와 굽는 중인 펫. */
    private ZzalPet hatching() {
        return PetFixture.hatching(1L, "여울", null, "k", LONG_AGO);
    }

    @Test
    @DisplayName("★ 이름 짓는 동안(DRAFT) 멈춘 것도 이어서 굽는다")
    void resumesDraft() {
        stuck(draft(), 1, 2);

        recovery.recover();

        verify(hatch).hatch(any(), any(), eq(V));
    }

    @Test
    @DisplayName("★ 굽기가 이미 끝난 DRAFT 는 건드리지 않는다 — 멈춘 게 아니라 이름을 기다리는 중이다")
    void skipsDraftWaitingForName() {
        stuck(draft(), 1, 5);

        recovery.recover();

        verify(hatch, never()).hatch(any(), any(), anyString());
    }

    @Test
    @DisplayName("★ 한 번도 안 구운 초안은 건드리지 않는다 — 부르지도 않은 굽기에 돈이 나간다")
    void skipsNeverBaked() {
        stuck(draft(), 0, 0);

        recovery.recover();

        verify(hatch, never()).hatch(any(), any(), anyString());
    }

    @Test
    @DisplayName("이름이 들어온 뒤(HATCHING) 멈춘 것은 전과 같이 이어서 굽는다")
    void resumesHatching() {
        stuck(hatching(), 1, 3);

        recovery.recover();

        verify(hatch).hatch(any(), any(), eq(V));
    }

    @Test
    @DisplayName("시도를 다 썼으면 다시 굽지 않고 실패로 끝낸다")
    void failsWhenAttemptsExhausted() {
        stuck(hatching(), 2, 3);

        recovery.recover();

        verify(hatch, never()).hatch(any(), any(), anyString());
        verify(recorder).markPetFailed(any());
    }

    @Test
    @DisplayName("★ 상한을 넘긴 값(3)도 다시 굽지 않는다 — 부등호가 == 이면 여기가 새어 계속 다시 굽는다 (M-8)")
    void beyondTheAttemptLimitAlsoStops() {
        stuck(hatching(), 3, 3);

        recovery.recover();

        verify(hatch, never()).hatch(any(), any(), anyString());
        verify(recorder).markPetFailed(any());
    }

    @Test
    @DisplayName("★★ 유예만큼 지난 것만 묻는다 — 지금 굽고 있는 알을 집으면 같은 그림에 돈이 두 번 (M-10)")
    void asksOnlyForWhatIsOlderThanTheGrace() {
        when(pets.findByPhaseInAndHatchStartedAtBefore(any(Collection.class), any())).thenReturn(List.of());

        recovery.recover();
        Instant after = Instant.now();

        org.mockito.ArgumentCaptor<Instant> cutoff = org.mockito.ArgumentCaptor.forClass(Instant.class);
        verify(pets).findByPhaseInAndHatchStartedAtBefore(any(Collection.class), cutoff.capture());
        // 12분 — app.zzal.recovery.grace-minutes 기본값. cutoff 는 호출 시각 기준이라
        // 그 사이에 흐른 시간만큼만 여유를 둔다(graceMinutes 를 0 으로 바꾸면 여기서 깨진다).
        assertThat(java.time.Duration.between(cutoff.getValue(), after).toSeconds())
                .isBetween(12 * 60L, 12 * 60L + 30);
    }

    @Test
    @DisplayName("★ 무엇을 찾는지 — DRAFT 와 HATCHING 둘뿐이다(ALIVE 를 집으면 살아 있는 아이를 다시 굽는다)")
    void looksOnlyForDraftAndHatching() {
        when(pets.findByPhaseInAndHatchStartedAtBefore(any(Collection.class), any())).thenReturn(List.of());

        recovery.recover();

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Collection<PetPhase>> phases =
                org.mockito.ArgumentCaptor.forClass(Collection.class);
        verify(pets).findByPhaseInAndHatchStartedAtBefore(phases.capture(), any());
        assertThat(phases.getValue()).containsExactlyInAnyOrder(PetPhase.DRAFT, PetPhase.HATCHING);
    }
}
