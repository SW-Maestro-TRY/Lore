package com.lore.zzal.pet;

import com.lore.zzal.PetFixture;
import com.lore.zzal.motion.MotionCatalog;
import com.lore.zzal.motion.MotionSpec;
import com.lore.zzal.pet.dto.PetResponses;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static com.lore.zzal.pet.AwakeClockTest.kst;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2층 그림이 READY 전이면 "연습 중"(#696).
 *
 * <ul>
 *   <li>카운터는 그대로 쌓인다 — 조건을 채워도 열리지 않을 뿐이다.</li>
 *   <li>조건을 채운 칸은 문구가 "연습 중이에요"(진행도는 꽉 찬 채), 아직 못 채운 칸은 평소 문구.</li>
 *   <li>그림 주소는 null — 그 판에 2층 파일이 없다.</li>
 *   <li>READY 가 되면 열리고 그림 주소가 나간다.</li>
 * </ul>
 */
@DisplayName("2층 READY 전 — 연습 중 표시")
class Layer2PracticingTest {

    private static final Instant T0 = kst("2026-10-08 12:00");
    private static final MotionCatalog CATALOG = new MotionCatalog("", "", "v1");

    private static ZzalPet aliveWaitingLayer2() {
        ZzalPet pet = PetFixture.hatching(1L, "여울", null, "k", T0);
        pet.markAlive("s", "i", T0);
        pet.resetLayer2(T0);           // 부화 완료 = 2층 PENDING(GenerationRecorder.markPetAlive 가 하는 일)
        pet.skipTutorial(T0);
        pet.markBasicBaked(1);
        return pet;
    }

    private static PetResponses.Motion motion(ZzalPet pet, String key) {
        return PetResponses.Detail.motions(pet, CATALOG, Map.of()).stream()
                .filter(m -> m.key().equals(key)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("★★ 조건을 채워도 READY 전엔 잠김 — 문구 '연습 중이에요' · 진행도 꽉 참 · 그림 없음")
    void conditionMetButNotReadyIsPracticing() {
        ZzalPet pet = aliveWaitingLayer2();
        for (int i = 0; i < 4; i++) {
            pet.pet(T0);                // 쓰다듬 4회 = petted 조건
        }
        MotionSpec petted = CATALOG.byKey("petted").orElseThrow();
        assertThat(UnlockRules.conditionMet(pet, petted, CATALOG)).isTrue();
        assertThat(UnlockRules.isUnlocked(pet, petted, CATALOG)).isFalse();
        assertThat(UnlockRules.openedLayerTwo(pet, CATALOG)).isZero();

        PetResponses.Motion m = motion(pet, "petted");
        assertThat(m.unlocked()).isFalse();
        assertThat(m.hint()).isEqualTo(PetResponses.Detail.LAYER2_PRACTICING_HINT);
        assertThat(m.progress().current()).isEqualTo(m.progress().target());
        assertThat(m.basicImageKey()).isNull();

        // 아직 못 채운 칸은 평소 문구 그대로
        PetResponses.Motion wash = motion(pet, "wash");
        assertThat(wash.hint()).isNotEqualTo(PetResponses.Detail.LAYER2_PRACTICING_HINT).isNotBlank();
        // 1층은 그대로 열려 있고 그림이 있다
        PetResponses.Motion base = motion(pet, "base");
        assertThat(base.unlocked()).isTrue();
        assertThat(base.basicImageKey()).isEqualTo("images/zzal/pets/null/basic/1/base.webp".replace("null", String.valueOf(pet.getId())));
    }

    @Test
    @DisplayName("★ READY 가 되면 그 순간부터 열린다 — 그림 주소도 나간다")
    void readyOpens() {
        ZzalPet pet = aliveWaitingLayer2();
        for (int i = 0; i < 4; i++) {
            pet.pet(T0);
        }
        pet.markBasicBaked(2);
        pet.markLayer2Ready(T0);

        PetResponses.Motion m = motion(pet, "petted");
        assertThat(m.unlocked()).isTrue();
        assertThat(m.hint()).isNull();
        assertThat(m.basicImageKey()).endsWith("/basic/2/petted.webp");
        assertThat(UnlockRules.unlockedKeys(pet, CATALOG)).contains("petted");
    }

    @Test
    @DisplayName("★★ 결함 표시(관리자) — 사용자 화면은 그대로다: READY 2층이 연습 중으로 잠기지 않는다(#702)")
    void flaggedKeepsUserView() {
        ZzalPet pet = aliveWaitingLayer2();
        for (int i = 0; i < 4; i++) {
            pet.pet(T0);
        }
        pet.markLayer2Ready(T0);
        pet.flagLayer2("빈 칸");

        assertThat(pet.isLayer2Flagged()).isTrue();
        assertThat(pet.getLayer2Status()).isEqualTo(Layer2Status.READY);
        assertThat(UnlockRules.unlockedKeys(pet, CATALOG)).contains("petted", "base", "sleep");
        assertThat(motion(pet, "petted").unlocked()).isTrue();
        assertThat(motion(pet, "petted").hint()).isNull();
    }

    @Test
    @DisplayName("★ 옛 펫(칸이 생기기 전 흐름)은 READY — 지금까지 열린 것이 그대로 열려 있다")
    void legacyPetIsReady() {
        ZzalPet pet = PetFixture.hatching(1L, "여울", null, "k", T0);
        pet.markAlive("s", "i", T0);
        assertThat(pet.getLayer2Status()).isEqualTo(Layer2Status.READY);
    }

    @Test
    @DisplayName("★ READY 알림 — 한 번 꺼내면 빈다")
    void announcementIsTakenOnce() {
        ZzalPet pet = aliveWaitingLayer2();
        pet.markLayer2Ready(T0);
        pet.announceLayer2(List.of(14), T0);
        assertThat(pet.takeLayer2JustUnlocked()).containsExactly(14);
        assertThat(pet.takeLayer2JustUnlocked()).isEmpty();
        assertThat(pet.getLayer2AnnouncedAt()).isEqualTo(T0);
    }
}
