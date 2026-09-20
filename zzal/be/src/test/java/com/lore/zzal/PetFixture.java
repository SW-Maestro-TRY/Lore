package com.lore.zzal;

import com.lore.zzal.pet.ZzalPet;
import java.time.Instant;

/**
 * 테스트에서 펫을 만드는 자리.
 *
 * <h3>★ 왜 필요한가</h3>
 * 실제 경로는 두 호출로 나뉜다 — 그림을 등록해 초안을 만들고(74초 벌기), 그다음 이름을 받는다.
 * 그런데 대부분의 테스트가 보려는 것은 <b>그 뒤의 규칙</b>(시계·돌봄·해금)이라, 두 줄을 매번
 * 적으면 부화 절차가 바뀔 때마다 테스트 수십 개가 함께 깨진다. 여기 한 곳만 고치면 되게 둔다.
 */
public final class PetFixture {

    private PetFixture() {
    }

    /**
     * 튜토리얼 8칸("졸린가 봐요") 차례로 맞춘다 — <b>그때만 재울 수 있다.</b>
     *
     * ★ 그 전에도 재울 수 있게 두면 낮잠 한 번(NAP_MAX = 1)을 미리 써 버려 정작 8칸에서
     *   재울 수가 없고, 튜토리얼이 영영 막힌다. 낮잠을 보는 테스트는 여기를 지나야 한다.
     */
    public static void readyForNap(ZzalPet pet) {
        atTutorialStep(pet, com.lore.zzal.pet.TutorialSchedule.Step.NAP);
    }

    /**
     * 튜토리얼을 원하는 칸에 맞춘다.
     *
     * ★ 칸은 <b>순서</b>라 앞 칸을 실제로 해야 넘어간다(정본 1.4). 규칙이 아니라 그 칸에서
     *   무슨 일이 일어나는지를 보려는 테스트는 여기로 자리만 잡는다.
     */
    public static void atTutorialStep(ZzalPet pet, com.lore.zzal.pet.TutorialSchedule.Step step) {
        org.springframework.test.util.ReflectionTestUtils.setField(pet, "tutorialStep", step.ordinal());
    }

    /**
     * 이름까지 받은, 격자를 굽는 중인 펫.
     *
     * ★ <b>번호를 넣어 둔다.</b> 운영에서 이 자리의 펫은 이미 저장돼 번호가 있고, 그림 주소는
     *   그 번호로 조립된다({@code images/zzal/pets/{id}/...}). 번호가 없는 펫으로 시험하면
     *   주소를 만드는 자리가 시험에서 통째로 빠지거나 {@code "null"} 이 낀 주소가 통과한다.
     */
    public static final Long PET_ID = 7L;

    public static ZzalPet hatching(Long userId, String name, String note, String imageKey, Instant now) {
        ZzalPet pet = ZzalPet.draft(userId, imageKey, now);
        pet.character(name, note, null, null, null, null, now);
        org.springframework.test.util.ReflectionTestUtils.setField(pet, "id", PET_ID);
        return pet;
    }
}
