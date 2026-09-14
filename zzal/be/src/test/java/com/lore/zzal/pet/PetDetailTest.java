package com.lore.zzal.pet;

import com.lore.zzal.PetFixture;
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
        ZzalPet pet = PetFixture.hatching(1L, "여울", "메모", "k", T0);
        pet.markAlive("s", "i", T0);
        return pet;
    }

    @Test
    @DisplayName("부화 중이면 ALIVE 블록은 전부 null, serverNow 는 있다")
    void hatchingHasNoAliveBlocks() {
        ZzalPet egg = PetFixture.hatching(1L, "알", null, "k", T0);
        PetResponses.Detail d = PetResponses.Detail.fromWithoutPieces(egg, "그리는 중", T0.plusSeconds(30), CATALOG);
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
        PetResponses.Detail d = PetResponses.Detail.fromWithoutPieces(baby(), null, T0, CATALOG);
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
            assertThat(x.key()).isEqualTo("eat_rice");
            assertThat(x.unlocked()).isFalse();
            assertThat(x.basicImageKey()).isNull();
            assertThat(x.hint()).isEqualTo("밥 주기 9회");
            assertThat(x.progress()).isEqualTo(new PetResponses.Progress(0, 9));
        });
        assertThat(m.get(16).seq()).isEqualTo(101);
        assertThat(m.get(16).layer()).isEqualTo("GIFT");
        assertThat(m.get(16).hint()).isEqualTo("함께한 첫 선물");
        assertThat(m.get(17).seq()).isEqualTo(102);
    }

    @Test
    @DisplayName("v2 부화 펫은 basic/{판}/{key}.webp 규약")
    void v2ImageKeys() {
        ZzalPet pet = baby();
        pet.setHatchPipelineVersion("v2");
        // ★ 판을 넣어야 하는 시험이 됐다 — 판이 0 이면 "아직 한 장도 안 구웠다" 라 키가 아예 안 나간다.
        org.springframework.test.util.ReflectionTestUtils.setField(pet, "basicRound", 1);
        PetResponses.Detail d = PetResponses.Detail.fromWithoutPieces(pet, null, T0, CATALOG);
        assertThat(d.motions().get(0).basicImageKey()).endsWith("/basic/1/base.webp");
        assertThat(d.motions().get(4).basicImageKey()).endsWith("/basic/1/sick.webp");
    }

    @Test
    @DisplayName("★ v4 부화 펫도 basic/{판}/{key}.webp 규약 — 옛 폴백으로 조용히 떨어지지 않는다")
    void v4ImageKeys() {
        ZzalPet pet = baby();
        pet.setHatchPipelineVersion("v4");
        org.springframework.test.util.ReflectionTestUtils.setField(pet, "basicRound", 1);
        PetResponses.Detail d = PetResponses.Detail.fromWithoutPieces(pet, null, T0, CATALOG);

        // "v2" 만 보고 판단하면 v4 펫은 옛 8상태 파일명으로 떨어져 그림이 하나도 안 뜬다 —
        // 빌드·기동·부화가 전부 성공한 뒤 화면에서만 드러나는 종류의 어긋남이다.
        assertThat(d.motions().get(0).basicImageKey()).endsWith("/basic/1/base.webp");
        assertThat(d.motions().get(4).basicImageKey()).endsWith("/basic/1/sick.webp");
    }

    @Test
    @DisplayName("★★ 한 장도 굽지 않은 펫(판 0)은 그림 주소를 안 준다 — 없는 파일을 가리키지 않는다")
    void unbakedPetCarriesNoBasicImageKey() {
        ZzalPet pet = baby();
        pet.setHatchPipelineVersion("v4");
        // 판 0 = 후처리가 한 번도 안 돌았다(첫 후처리가 1 로 올린다).

        PetResponses.Detail d = PetResponses.Detail.fromWithoutPieces(pet, null, T0, CATALOG);

        // 주소를 주면 화면은 "주소가 왔으니 그림이 있겠지" 하고 대체 그림을 띄울 기회를 놓친다.
        // 오류는 서버에도 화면에도 안 난다 — 빈 무대로만 드러난다.
        assertThat(d.motions().get(0).key()).isEqualTo("base");
        assertThat(d.motions().get(0).basicImageKey()).isNull();
        assertThat(d.motions()).allSatisfy(m -> assertThat(m.basicImageKey()).isNull());
        // 앵커도 같은 기준이다 — 그림이 없는데 그 그림을 설명하는 앵커만 있을 수는 없다.
        assertThat(d.anchorsKey()).isNull();

        // ★ 판이 한 번이라도 올라가면 그때부터 준다 — 같은 펫, 같은 버전, 판만 다르다.
        org.springframework.test.util.ReflectionTestUtils.setField(pet, "basicRound", 1);
        PetResponses.Detail baked = PetResponses.Detail.fromWithoutPieces(pet, null, T0, CATALOG);
        assertThat(baked.motions().get(0).basicImageKey()).isEqualTo("images/zzal/pets/7/basic/1/base.webp");

        // ★ 잠긴 2층도 판만 있으면 주소가 온다 — null 의 뜻은 "안 배웠다" 가 아니라 "그림이 없다" 다.
        PetResponses.Motion lockedSecondFloor = baked.motions().get(8);
        assertThat(lockedSecondFloor.key()).isEqualTo("eat_rice");
        assertThat(lockedSecondFloor.unlocked()).isFalse();
        assertThat(lockedSecondFloor.basicImageKey()).isEqualTo("images/zzal/pets/7/basic/1/eat_rice.webp");
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
        PetResponses.Detail d = PetResponses.Detail.fromWithoutPieces(pet, null, T0.plus(Duration.ofMinutes(1)), CATALOG);
        assertThat(d.clock().clockStartedAt()).isNull();                        // ★ 튜토리얼 중 = 시계 안 켜짐
        assertThat(d.clock().sleeping()).isFalse();
        assertThat(d.clock().autoSleepAt()).isNull();                           // ★ 튜토리얼 중엔 자동 취침 없음
        // ★ 첫 칸(밥) 차례에는 아직 안 졸리다 — 재우기가 안 열린다
        assertThat(d.clock().canSleep()).isFalse();
        assertThat(d.clock().sleepWindowOpensAt()).isNull();

        // 8칸(낮잠) 차례가 되면 "지금" 잘 수 있다
        com.lore.zzal.PetFixture.readyForNap(pet);
        assertThat(PetResponses.Detail.fromWithoutPieces(pet, null, T0.plus(Duration.ofMinutes(1)), CATALOG)
                .clock().sleepWindowOpensAt()).isEqualTo(T0.plus(Duration.ofMinutes(1)));
        pet.sleep(T0.plus(Duration.ofMinutes(1)));
        pet.wake(T0.plus(Duration.ofMinutes(2)));                                 // ★ 낮잠은 사용자가 깨운다
        // 낮잠을 썼고 아직 튜토리얼 중 — 열릴 창이 없다(밤잠은 시계가 켜져야 생긴다)
        assertThat(PetResponses.Detail.fromWithoutPieces(pet, null, T0.plus(Duration.ofMinutes(12)), CATALOG)
                .clock().sleepWindowOpensAt()).isNull();
        assertThat(d.daysTogether()).isEqualTo(1);
        assertThat(d.gauges()).isEqualTo(new PetResponses.Gauges(0, 3, 4, 0));     // ★ 배부름 0 으로 시작
        assertThat(d.food()).isEqualTo(new PetResponses.Food(3, null));
        assertThat(d.mood()).isEqualTo("HUNGRY");   // ★ 배부름 0 으로 시작한다(튜토리얼 첫 칸이 밥)
        assertThat(d.features()).isEqualTo(new PetResponses.Features(true, true, false, false, false, false, false));
        // ★ daysLeft 는 항상 0 — 첫 선물은 날짜가 아니라 튜토리얼 완주로 열린다.
        //   옛 3일 규칙으로 계산한 값을 내려보내면 화면이 뜻 없는 카운트다운을 그린다.
        assertThat(d.firstGift()).isEqualTo(new PetResponses.FirstGift("LOCKED", 0));
        assertThat(d.chatSummary().nextAt()).isEqualTo(T0.plus(Duration.ofHours(1)));   // 기상(부화)+1h
        assertThat(d.tutorial().active()).isTrue();
        assertThat(d.tutorial().steps().get(0).current()).isTrue();
        assertThat(d.justUnlocked()).isEmpty();
        assertThat(d.sick()).isNull();
        assertThat(d.pieces()).isNull();
    }

    @Test
    @DisplayName("자는 중엔 wakeWindowOpensAt·autoWakeAt 이 채워지고 sleepWindowOpensAt 은 비운다")
    void sleepingClock() {
        ZzalPet pet = baby();
        pet.skipTutorial(T0);                       // ★ 시계가 켜져야 자동 취침이 있다
        Instant t = kst("2026-09-06 00:00");
        pet.settle(t);
        PetResponses.Detail d = PetResponses.Detail.fromWithoutPieces(pet, null, t, CATALOG);
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
        // ★ 굽는 중이던 줄만 검수 대기로 간다(1.9). 운영은 claim 이 DB 에서 BAKING 으로 집는다.
        org.springframework.test.util.ReflectionTestUtils.setField(roll, "status", com.lore.zzal.motion.MotionStatus.BAKING);
        roll.toReview("images/zzal/pets/7/motions/101/motion.webp", null, null,
com.lore.zzal.motion.MotionSource.API,
                com.lore.zzal.motion.GateVerdict.REVIEW, "n", "g0");

        // 1) 검수 대기 — 사용자에게는 "연습 중", 그림 없음
        PetResponses.Detail waiting = PetResponses.Detail.fromWithoutPieces(pet, null, T0, CATALOG, Map.of(1, base, 101, roll), List.of());
        assertThat(waiting.motions().get(16).advanced().status()).isEqualTo("PRACTICING");
        assertThat(waiting.motions().get(16).advanced().imageKey()).isNull();
        assertThat(waiting.learnedToday()).isEmpty();
        assertThat(waiting.baking()).isEqualTo("PRACTICING");
        assertThat(waiting.firstGift().status()).isEqualTo("BAKING");
        assertThat(waiting.features().album()).isFalse();

        // 2) 검수 통과했지만 아직 도착 전 — 여전히 안 보인다
        roll.approve(T0);
        PetResponses.Detail approved = PetResponses.Detail.fromWithoutPieces(pet, null, T0, CATALOG, Map.of(1, base, 101, roll), List.of());
        assertThat(approved.motions().get(16).advanced().status()).isEqualTo("PRACTICING");
        assertThat(approved.motions().get(16).advanced().imageKey()).isNull();

        // 3) 도착 — 그때 보이고, learnedToday 에 실리고, 앨범이 열린다
        roll.reveal(T0);
        PetResponses.Detail arrived = PetResponses.Detail.fromWithoutPieces(pet, null, T0, CATALOG, Map.of(1, base, 101, roll), List.of());
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
        PetResponses.Detail seen = PetResponses.Detail.fromWithoutPieces(pet, null, T0, CATALOG, Map.of(1, base, 101, roll), List.of());
        assertThat(seen.learnedToday()).isEmpty();
        assertThat(seen.motions().get(16).advanced().seen()).isTrue();
    }

    @Test
    @DisplayName("★★ 첫 선물은 튜토리얼을 끝내는 순간 LOCKED → WAITING — 날짜가 아니다")
    void firstGiftFollowsTheTutorialNotTheCalendar() {
        ZzalPet pet = baby();
        assertThat(pet.isInTutorial()).isTrue();
        assertThat(PetResponses.Detail.fromWithoutPieces(pet, null, T0, CATALOG).firstGift())
                .isEqualTo(new PetResponses.FirstGift("LOCKED", 0));

        pet.skipTutorial(T0);

        assertThat(PetResponses.Detail.fromWithoutPieces(pet, null, T0, CATALOG).firstGift())
                .as("함께한 날은 여전히 첫날이다 — 그래도 열린다")
                .isEqualTo(new PetResponses.FirstGift("WAITING", 0));
    }

    @Test
    @DisplayName("★★ 잠긴 2층 여덟 칸은 <b>전부</b> 진행도를 보여준다 — 숨길 이유가 있던 조건이 없어졌다")
    void everyLockedLayerTwoShowsProgress() {
        // 진행도를 감추는 규칙은 "케어 미스 0인 날" 같은 숨은 수치 때문이었다. 지금 2층 여덟은
        // 전부 사용자가 직접 한 행동(밥·간식·청소·목욕·채팅·쓰다듬·게임·깨우기)이라 감출 것이 없다.
        ZzalPet pet = baby();

        PetResponses.Detail d = PetResponses.Detail.fromWithoutPieces(pet, null, T0, CATALOG);

        assertThat(d.motions().stream().filter(m -> "BASIC_2".equals(m.layer())))
                .hasSize(8)
                .allSatisfy(m -> {
                    assertThat(m.unlocked()).as("%s 는 아직 잠겨 있어야 한다", m.key()).isFalse();
                    assertThat(m.hint()).as("%s 의 조건 문구", m.key()).isNotBlank();
                    assertThat(m.progress()).as("%s 의 진행도", m.key()).isNotNull();
                });
    }

    @Test
    @DisplayName("★★ 3층 전에는 pieces 가 null — 조각 칸이 화면에 없다")
    void piecesNullBeforeTierThree() {
        ZzalPet pet = baby();
        PetResponses.Detail before = PetResponses.Detail.fromWithoutPieces(pet, null, T0, CATALOG);
        assertThat(before.pieces()).isNull();
        assertThat(before.features().pieces()).isFalse();
        assertThat(before.goodDay()).isFalse();

        pet.enablePieces(T0);
        PetResponses.Detail after = PetResponses.Detail.fromWithoutPieces(pet, null, T0, CATALOG);
        assertThat(after.pieces()).isNotNull();
        assertThat(after.features().pieces()).isTrue();
        assertThat(after.pieces().count()).isZero();
        assertThat(after.pieces().bonus()).isFalse();
        // ★ fromWithoutPieces 로 불렀으므로 빈 판이다 — 이름이 그 사실을 말한다.
        //   사용자에게 나가는 자리에서는 조각 줄을 넘기는 갈래를 쓴다.
        assertThat(after.pieces().foodProgress().current()).isZero();
    }

    @Test
    @DisplayName("baking — 밤 큐에 오르면 QUEUED, 아무 일도 없으면 NONE")
    void bakingSummary() {
        ZzalPet pet = baby();
        ZzalMotion roll = ZzalMotion.forCatalog(7L, CATALOG.bySeq(101).orElseThrow(), T0);
        assertThat(PetResponses.Detail.fromWithoutPieces(pet, null, T0, CATALOG, Map.of(101, roll), List.of()).baking())
                .isEqualTo("NONE");
        roll.queue(java.time.LocalDate.of(2026, 9, 5));
        assertThat(PetResponses.Detail.fromWithoutPieces(pet, null, T0, CATALOG, Map.of(101, roll), List.of()).baking())
                .isEqualTo("QUEUED");
    }

    @Test
    @DisplayName("★★ 잠긴 2층도 그림 주소를 내려보낸다 — 화면이 '그림이 없다' 와 '아직 안 배웠다' 를 구분해야 한다")
    void lockedBasicStillCarriesItsImageKey() {
        ZzalPet pet = baby();
        pet.setHatchPipelineVersion("v4");
        org.springframework.test.util.ReflectionTestUtils.setField(pet, "basicRound", 2);

        PetResponses.Detail d = PetResponses.Detail.fromWithoutPieces(pet, null, T0, CATALOG);
        PetResponses.Motion eatRice = d.motions().get(8);

        // 2층 8종은 부화 때 1층과 함께 구워진다 — 잠겨 있어도 그림은 이미 있다.
        // 잠겼다고 null 을 주면 화면이 "1층 + 소품으로 그리자" 를 고를 수 없다.
        assertThat(eatRice.key()).isEqualTo("eat_rice");
        assertThat(eatRice.unlocked()).isFalse();
        assertThat(eatRice.basicImageKey()).isEqualTo("images/zzal/pets/7/basic/2/eat_rice.webp");

        // ★ 선물만 여전히 null — 선물은 기본 그림이 아니라 16프레임 움짤이고 주소가 다른 자리다.
        assertThat(d.motions().get(16).seq()).isEqualTo(101);
        assertThat(d.motions().get(16).basicImageKey()).isNull();
    }

    @Test
    @DisplayName("★ 기본 그림 주소에 판이 들어간다 — 다시 구우면 같은 주소를 덮어쓰지 않는다")
    void basicImageKeyCarriesTheRound() {
        ZzalPet pet = baby();
        pet.setHatchPipelineVersion("v4");
        org.springframework.test.util.ReflectionTestUtils.setField(pet, "basicRound", 1);
        String first = PetResponses.Detail.fromWithoutPieces(pet, null, T0, CATALOG)
                .motions().get(0).basicImageKey();

        org.springframework.test.util.ReflectionTestUtils.setField(pet, "basicRound", 2);
        String second = PetResponses.Detail.fromWithoutPieces(pet, null, T0, CATALOG)
                .motions().get(0).basicImageKey();

        assertThat(first).isEqualTo("images/zzal/pets/7/basic/1/base.webp");
        assertThat(second).isNotEqualTo(first);
    }

    @Test
    @DisplayName("★ anchorsKey — 전체 URL 이 아니라 키이고, 그림과 같은 판을 가리킨다")
    void anchorsKeyIsAKeyOfTheSameRound() {
        ZzalPet pet = baby();
        pet.setHatchPipelineVersion("v4");
        org.springframework.test.util.ReflectionTestUtils.setField(pet, "basicRound", 3);

        PetResponses.Detail d = PetResponses.Detail.fromWithoutPieces(pet, null, T0, CATALOG);
        assertThat(d.anchorsKey()).isEqualTo("images/zzal/pets/7/basic/3/anchors.json");
        assertThat(d.anchorsKey()).doesNotStartWith("http");
        assertThat(d.motions().get(0).basicImageKey()).startsWith("images/zzal/pets/7/basic/3/");

        // 앵커를 안 내는 버전이거나 아직 한 판도 안 구웠으면 null — 없는 주소를 주지 않는다.
        pet.setHatchPipelineVersion("v2");
        assertThat(PetResponses.Detail.fromWithoutPieces(pet, null, T0, CATALOG).anchorsKey()).isNull();
    }

    @Test
    @DisplayName("★ 말투·장르를 상세 응답에 싣는다 — 저장만 하고 안 내려보내면 화면이 다시 물어봐야 한다")
    void toneAndGenreAreReturned() {
        ZzalPet pet = ZzalPet.draft(1L, "k", T0);
        pet.character("여울", null, null, "비 오는 도시", "무뚝뚝한 존댓말", "느와르", T0);
        org.springframework.test.util.ReflectionTestUtils.setField(pet, "id", 7L);
        pet.markAlive("s", "i", T0);

        PetResponses.Detail d = PetResponses.Detail.fromWithoutPieces(pet, null, T0, CATALOG);
        assertThat(d.tone()).isEqualTo("무뚝뚝한 존댓말");
        assertThat(d.genre()).isEqualTo("느와르");
        assertThat(d.world()).isEqualTo("비 오는 도시");
    }

    @Test
    @DisplayName("★ 선물 움짤의 가로·세로를 함께 내려보낸다 — 판마다 캔버스가 다르다")
    void giftCarriesItsCanvasSize() {
        ZzalPet pet = baby();
        ZzalMotion roll = ZzalMotion.forCatalog(7L, CATALOG.bySeq(101).orElseThrow(), T0);
        org.springframework.test.util.ReflectionTestUtils.setField(roll, "status", MotionStatus.BAKING);
        roll.toReview("images/zzal/pets/7/motions/101/1/motion.webp", 295, 321,
                com.lore.zzal.motion.MotionSource.API,
                com.lore.zzal.motion.GateVerdict.REVIEW, "n", "g0");
        roll.approve(T0);

        // 도착 전에는 크기도 안 나간다 — 주소와 짝이어야 한다.
        PetResponses.Detail before =
                PetResponses.Detail.fromWithoutPieces(pet, null, T0, CATALOG, Map.of(101, roll), List.of());
        assertThat(before.motions().get(16).advanced().width()).isNull();

        roll.reveal(T0);
        PetResponses.Detail after =
                PetResponses.Detail.fromWithoutPieces(pet, null, T0, CATALOG, Map.of(101, roll), List.of());
        assertThat(after.motions().get(16).advanced().width()).isEqualTo(295);
        assertThat(after.motions().get(16).advanced().height()).isEqualTo(321);
        assertThat(after.learnedToday()).singleElement().satisfies(l -> {
            assertThat(l.width()).isEqualTo(295);
            assertThat(l.height()).isEqualTo(321);
        });
    }
}
