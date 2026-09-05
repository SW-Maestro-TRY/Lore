package com.lore.zzal.pet;

import com.lore.zzal.motion.MotionCatalog;
import com.lore.zzal.motion.MotionStatus;
import com.lore.zzal.motion.ZzalMotion;
import com.lore.zzal.pet.dto.PetResponses;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static com.lore.zzal.pet.AwakeClockTest.kst;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * `PetDetail` v2 — api-v2.md 2절과의 대조.
 *
 * ★ 프론트 `lib/pet.ts` 가 이 모양과 필드명 단위로 맞물린다. 여기서 깨지면 계약이 깨진 것이다.
 */
@DisplayName("PetDetail v2 — 계약 대조")
class PetDetailTest {

    private static final Instant T0 = kst("2026-09-05 12:00");
    private static final MotionCatalog CATALOG = new MotionCatalog("", "", "v1");

    private static ZzalPet baby() {
        ZzalPet pet = ZzalPet.hatch(1L, "여울", "메모", "k", T0);
        pet.markAlive("s", "i", T0);
        return pet;
    }

    @Test
    @DisplayName("부화 중이면 ALIVE 블록은 전부 null, serverNow 는 있다")
    void hatchingHasNoAliveBlocks() {
        ZzalPet egg = ZzalPet.hatch(1L, "알", null, "k", T0);
        PetResponses.Detail d = PetResponses.Detail.from(egg, "그리는 중", T0.plusSeconds(30), CATALOG);
        assertThat(d.phase()).isEqualTo("HATCHING");
        assertThat(d.ready()).isFalse();
        assertThat(d.step()).isEqualTo("그리는 중");
        assertThat(d.elapsedSeconds()).isEqualTo(30);
        assertThat(d.serverNow()).isEqualTo(T0.plusSeconds(30));
        assertThat(d.clock()).isNull();
        assertThat(d.tutorial()).isNull();
        // 리스트는 null 이 아니라 빈 목록(해석 20)
        assertThat(d.motions()).isEmpty();
        assertThat(d.justUnlocked()).isEmpty();
        assertThat(d.learnedToday()).isEmpty();
    }

    @Test
    @DisplayName("motions 18칸 — 1층 열림·2층 잠김(이름+조건+진행)·선물 2")
    void eighteenMotions() {
        PetResponses.Detail d = PetResponses.Detail.from(baby(), null, T0, CATALOG);
        List<PetResponses.Motion> m = d.motions();
        assertThat(m).hasSize(18);
        assertThat(m.get(0)).satisfies(x -> {
            assertThat(x.seq()).isEqualTo(1);
            assertThat(x.key()).isEqualTo("base");
            assertThat(x.layer()).isEqualTo("BASIC_1");
            assertThat(x.unlocked()).isTrue();
            assertThat(x.basicImageKey()).endsWith("/idle.webp");      // v1 부화 → 8상태 파일명 폴백
            assertThat(x.hint()).isNull();
            assertThat(x.progress()).isNull();
            assertThat(x.advanced().status()).isEqualTo("NONE");
        });
        assertThat(m.get(4).key()).isEqualTo("sick");
        assertThat(m.get(4).basicImageKey()).isNull();                   // v1 에 없는 자세 → 화면 폴백
        assertThat(m.get(8)).satisfies(x -> {
            assertThat(x.seq()).isEqualTo(9);
            assertThat(x.key()).isEqualTo("tilt");
            assertThat(x.unlocked()).isFalse();
            assertThat(x.basicImageKey()).isNull();
            assertThat(x.hint()).isEqualTo("채팅 응답 1회");
            assertThat(x.progress()).isEqualTo(new PetResponses.Progress(0, 1));
        });
        assertThat(m.get(16).seq()).isEqualTo(101);
        assertThat(m.get(16).layer()).isEqualTo("GIFT");
        assertThat(m.get(16).hint()).isEqualTo("3일이나 함께해서…");
        assertThat(m.get(17).seq()).isEqualTo(102);
    }

    @Test
    @DisplayName("v2 부화 펫은 basic/{key}.webp 규약")
    void v2ImageKeys() {
        ZzalPet pet = baby();
        pet.setHatchPipelineVersion("v2");
        PetResponses.Detail d = PetResponses.Detail.from(pet, null, T0, CATALOG);
        assertThat(d.motions().get(0).basicImageKey()).endsWith("/basic/base.webp");
        assertThat(d.motions().get(4).basicImageKey()).endsWith("/basic/sick.webp");
    }

    @Test
    @DisplayName("친밀도 percent 는 10 단위 내림, tier 는 LOW ≤30 · MID 40~70 · HIGH ≥80 (해석 10)")
    void intimacyTiers() {
        assertThat(PetResponses.Intimacy.of(0)).isEqualTo(new PetResponses.Intimacy(0, 0, "LOW"));
        assertThat(PetResponses.Intimacy.of(120)).isEqualTo(new PetResponses.Intimacy(120, 10, "LOW"));
        assertThat(PetResponses.Intimacy.of(399)).isEqualTo(new PetResponses.Intimacy(399, 30, "LOW"));
        assertThat(PetResponses.Intimacy.of(400)).isEqualTo(new PetResponses.Intimacy(400, 40, "MID"));
        assertThat(PetResponses.Intimacy.of(799)).isEqualTo(new PetResponses.Intimacy(799, 70, "MID"));
        assertThat(PetResponses.Intimacy.of(800)).isEqualTo(new PetResponses.Intimacy(800, 80, "HIGH"));
        assertThat(PetResponses.Intimacy.of(999)).isEqualTo(new PetResponses.Intimacy(999, 100, "HIGH"));
    }

    @Test
    @DisplayName("clock·features·tutorial·firstGift·chatSummary 블록")
    void blocks() {
        ZzalPet pet = baby();
        PetResponses.Detail d = PetResponses.Detail.from(pet, null, T0.plus(Duration.ofMinutes(1)), CATALOG);
        assertThat(d.clock().babyUntil()).isEqualTo(T0.plus(Duration.ofMinutes(60)));
        assertThat(d.clock().sleeping()).isFalse();
        assertThat(d.clock().canSleep()).isTrue();                              // 아기 낮잠
        assertThat(d.clock().autoSleepAt()).isEqualTo(kst("2026-09-05 23:00"));
        assertThat(d.clock().sleepWindowOpensAt()).isEqualTo(T0.plus(Duration.ofMinutes(1)));   // 낮잠 가능 → 지금(해석·2절)
        pet.sleep(T0.plus(Duration.ofMinutes(1)));
        pet.settle(T0.plus(Duration.ofMinutes(12)));                              // 낮잠 씀
        assertThat(PetResponses.Detail.from(pet, null, T0.plus(Duration.ofMinutes(12)), CATALOG)
                .clock().sleepWindowOpensAt()).isEqualTo(kst("2026-09-05 19:00"));
        assertThat(d.daysTogether()).isEqualTo(1);
        assertThat(d.gauges()).isEqualTo(new PetResponses.Gauges(1, 3, 4, 0));
        assertThat(d.food()).isEqualTo(new PetResponses.Food(3, null));
        assertThat(d.mood()).isEqualTo("NORMAL");
        assertThat(d.features()).isEqualTo(new PetResponses.Features(true, true, false, false, false, false, false));
        assertThat(d.firstGift()).isEqualTo(new PetResponses.FirstGift("LOCKED", 2));
        assertThat(d.chatSummary().nextAt()).isEqualTo(T0.plus(Duration.ofHours(1)));   // 기상(부화)+1h
        assertThat(d.tutorial().active()).isTrue();
        assertThat(d.tutorial().steps().get(0).current()).isTrue();
        assertThat(d.justUnlocked()).isEmpty();
        assertThat(d.settings().leaveEnabled()).isTrue();
        assertThat(d.sick()).isNull();
        assertThat(d.pieces()).isNull();
    }

    @Test
    @DisplayName("자는 중엔 wakeWindowOpensAt·autoWakeAt 이 채워지고 sleepWindowOpensAt 은 비운다")
    void sleepingClock() {
        ZzalPet pet = baby();
        Instant t = kst("2026-09-06 00:00");
        pet.settle(t);
        PetResponses.Detail d = PetResponses.Detail.from(pet, null, t, CATALOG);
        assertThat(d.clock().sleeping()).isTrue();
        assertThat(d.clock().sleepKind()).isEqualTo("NIGHT");
        assertThat(d.clock().wakeWindowOpensAt()).isEqualTo(kst("2026-09-06 07:00"));
        assertThat(d.clock().autoWakeAt()).isEqualTo(kst("2026-09-06 10:00"));
        assertThat(d.clock().sleepWindowOpensAt()).isNull();
        assertThat(d.clock().canWake()).isFalse();
    }

    @Test
    @DisplayName("★ 검수 중인 그림은 절대 안 내려간다 — 도착(revealedAt) 뒤에만 imageKey")
    void advancedFromRows() {
        ZzalPet pet = baby();
        ZzalMotion base = ZzalMotion.forCatalog(7L, CATALOG.bySeq(1).orElseThrow(), T0);
        ZzalMotion roll = ZzalMotion.forCatalog(7L, CATALOG.bySeq(101).orElseThrow(), T0);
        roll.toReview("images/zzal/pets/7/motions/101/motion.webp", com.lore.zzal.motion.MotionSource.API,
                com.lore.zzal.motion.GateVerdict.REVIEW, "n", "g0");

        // 1) 검수 대기 — 사용자에게는 "연습 중", 그림 없음
        PetResponses.Detail waiting = PetResponses.Detail.from(pet, null, T0, CATALOG, Map.of(1, base, 101, roll), List.of());
        assertThat(waiting.motions().get(16).advanced().status()).isEqualTo("PRACTICING");
        assertThat(waiting.motions().get(16).advanced().imageKey()).isNull();
        assertThat(waiting.learnedToday()).isEmpty();
        assertThat(waiting.baking()).isEqualTo("PRACTICING");
        assertThat(waiting.firstGift().status()).isEqualTo("BAKING");
        assertThat(waiting.features().album()).isFalse();

        // 2) 검수 통과했지만 아직 도착 전 — 여전히 안 보인다
        roll.approve(T0);
        PetResponses.Detail approved = PetResponses.Detail.from(pet, null, T0, CATALOG, Map.of(1, base, 101, roll), List.of());
        assertThat(approved.motions().get(16).advanced().status()).isEqualTo("PRACTICING");
        assertThat(approved.motions().get(16).advanced().imageKey()).isNull();

        // 3) 도착 — 그때 보이고, learnedToday 에 실리고, 앨범이 열린다
        roll.reveal(T0);
        PetResponses.Detail arrived = PetResponses.Detail.from(pet, null, T0, CATALOG, Map.of(1, base, 101, roll), List.of());
        assertThat(arrived.motions().get(0).advanced().status()).isEqualTo("NONE");
        assertThat(arrived.motions().get(0).advanced().imageKey()).isNull();
        assertThat(arrived.motions().get(16).advanced().status()).isEqualTo(MotionStatus.OPEN.name());
        assertThat(arrived.motions().get(16).advanced().imageKey()).endsWith("/motions/101/motion.webp");
        assertThat(arrived.motions().get(16).advanced().seen()).isFalse();
        assertThat(arrived.motions().get(2).advanced().status()).isEqualTo("NONE");   // 행이 없는 칸
        assertThat(arrived.learnedToday()).singleElement()
                .satisfies(l -> assertThat(l.seq()).isEqualTo(101));
        assertThat(arrived.firstGift().status()).isEqualTo("OPEN");
        assertThat(arrived.features().album()).isTrue();
        assertThat(arrived.baking()).isEqualTo("NONE");

        // 4) 확인을 누르면 learnedToday 에서 빠진다(도감에는 그대로 남는다)
        roll.markSeen(T0);
        PetResponses.Detail seen = PetResponses.Detail.from(pet, null, T0, CATALOG, Map.of(1, base, 101, roll), List.of());
        assertThat(seen.learnedToday()).isEmpty();
        assertThat(seen.motions().get(16).advanced().seen()).isTrue();
    }

    @Test
    @DisplayName("★★ \"케어 미스 0인 날\" 진행도는 안 내려간다 — 힌트만(숨은 수치를 되짚게 하면 안 된다)")
    void zeroMissProgressIsHidden() {
        ZzalPet pet = baby();
        org.springframework.test.util.ReflectionTestUtils.setField(pet, "zeroMissDays", 2);

        PetResponses.Detail d = PetResponses.Detail.from(pet, null, T0, CATALOG);
        PetResponses.Motion smileIdle = d.motions().stream().filter(m -> m.seq() == 15).findFirst().orElseThrow();

        assertThat(smileIdle.unlocked()).isFalse();
        assertThat(smileIdle.hint()).isNotBlank();      // 무엇을 해야 열리는지는 알려준다
        assertThat(smileIdle.progress()).isNull();      // ★ 몇 번째인지는 말하지 않는다

        // 다른 잠긴 칸은 그대로 진행도를 준다(비교군)
        PetResponses.Motion tilt = d.motions().stream().filter(m -> m.seq() == 9).findFirst().orElseThrow();
        assertThat(tilt.progress()).isNotNull();
    }

    @Test
    @DisplayName("baking — 밤 큐에 오르면 QUEUED, 아무 일도 없으면 NONE")
    void bakingSummary() {
        ZzalPet pet = baby();
        ZzalMotion roll = ZzalMotion.forCatalog(7L, CATALOG.bySeq(101).orElseThrow(), T0);
        assertThat(PetResponses.Detail.from(pet, null, T0, CATALOG, Map.of(101, roll), List.of()).baking())
                .isEqualTo("NONE");
        roll.queue(java.time.LocalDate.of(2026, 9, 5));
        assertThat(PetResponses.Detail.from(pet, null, T0, CATALOG, Map.of(101, roll), List.of()).baking())
                .isEqualTo("QUEUED");
    }
}
