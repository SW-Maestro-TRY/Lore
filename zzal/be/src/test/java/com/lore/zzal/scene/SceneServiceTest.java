package com.lore.zzal.scene;

import com.lore.zzal.PetFixture;
import com.lore.zzal.motion.MotionCatalog;
import com.lore.zzal.pet.SleepKind;
import com.lore.zzal.pet.ZzalPet;
import com.lore.zzal.pet.ZzalRules;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static com.lore.zzal.pet.AwakeClockTest.kst;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 혼자 논 장면 — 실패 주입(verify-failure-paths).
 *
 * ★ 여기서 지키는 것 넷 — (1) <b>자는 시간은 부재가 아니다</b>(안 그러면 매일 아침 장면이 두 컷씩 쌓인다),
 *   (2) 여행 중에는 방에 없다, (3) 같은 시각이면 같은 장면(정산이 같은 구간을 여러 번 걸어도),
 *   (4) 넷째 컷이 생기면 <b>가장 오래된 것이 사라진다</b>. 넷 다 정상 경로에서는 안 도는 분기다.
 */
@DisplayName("혼자 논 장면 — 실패 주입")
class SceneServiceTest {

    private static final Instant T0 = kst("2026-09-05 12:00");
    private static final Long PET_ID = 7L;

    private final List<ZzalScene> stored = new ArrayList<>();
    private ZzalSceneRepository repository;
    private SceneService service;

    @BeforeEach
    void setUp() {
        repository = mock(ZzalSceneRepository.class);
        when(repository.save(any())).thenAnswer(i -> {
            ZzalScene s = i.getArgument(0);
            ReflectionTestUtils.setField(s, "id", (long) (stored.size() + 1));
            stored.add(s);
            return s;
        });
        when(repository.findByPetIdOrderBySceneAtDescIdDesc(any())).thenAnswer(i -> stored.stream()
                .sorted(Comparator.comparing(ZzalScene::getSceneAt).thenComparing(ZzalScene::getId).reversed())
                .toList());
        org.mockito.Mockito.doAnswer(i -> {
            stored.remove(i.<ZzalScene>getArgument(0));
            return null;
        }).when(repository).delete(any());
        service = new SceneService(repository, new MotionCatalog("", "", "v1"));
    }

    /** 11:00 에 부화해 정오에 어린이가 된 펫(ZzalPetTest 와 같은 모양). */
    private ZzalPet child() {
        Instant hatched = T0.minus(Duration.ofMinutes(60));
        ZzalPet pet = PetFixture.hatching(1L, "여울", null, "images/zzal/abc", hatched);
        pet.markAlive("images/zzal/sheet", "생김새", hatched);
        pet.skipTutorial(hatched);
        ReflectionTestUtils.setField(pet, "id", PET_ID);
        pet.settle(T0);
        // 아기 60분을 그냥 보내 게이지가 바닥이므로 정오에 한 번 채워 둔다(ZzalPetTest.child 와 같은 모양)
        pet.feed(T0);
        pet.feed(T0);
        pet.feed(T0);
        pet.snack(T0);
        pet.snack(T0);
        pet.snack(T0);
        pet.clean(T0);
        return pet;
    }

    @Test
    @DisplayName("★★ 자는 시간은 부재로 안 센다 — 밤을 넘겨도 깨어 있던 만큼만 컷이 남는다")
    void sleepDoesNotCountAsAbsence() {
        ZzalPet pet = child();

        // 아기 60분(11:00~12:00) + 12:00~23:00 = 12시간 → 자동 취침 → 다음 날 10:00 자동 기상 → 12:00 = +2시간
        // 벽시계로는 25시간이지만 깨어 있는 시간은 14시간이다
        pet.settle(kst("2026-09-06 12:00"));

        assertThat(pet.getAbsenceAwakeSec()).isEqualTo(14 * 3600L);
        assertThat(pet.pendingScenes()).isEqualTo(3);         // 자는 11시간까지 셌다면 6컷이 됐을 것이다
    }

    @Test
    @DisplayName("★ 남는 것은 최대 3컷 — 열흘을 비워도 마찬가지고, 쓴 시간만큼만 덜어낸다")
    void keepsAtMostThree() {
        ZzalPet pet = child();
        // ★ 열흘을 비우면 떠남 예고·여행이 걸린다(PR-11) — 이 테스트의 관심사가 아니므로 떠남을 끈다
        pet.setLeaveEnabled(false);
        pet.settle(kst("2026-09-15 12:00"));                  // 열흘
        int pending = pet.pendingScenes();
        assertThat(pending).isGreaterThan(3);

        int made = service.recordAbsence(pet, kst("2026-09-15 12:00"));

        assertThat(made).isEqualTo(3);
        assertThat(stored).hasSize(3);
        assertThat(pet.pendingScenes()).isZero();             // 쌓인 청크는 다 소진됐다
    }

    @Test
    @DisplayName("★ 세 컷을 한 번에 남겨도 정리는 한 번만 — 컷마다 표를 다시 읽지 않는다")
    void trimsOnce() {
        ZzalPet pet = child();
        ReflectionTestUtils.setField(pet, "absenceAwakeSec", 12 * 3600L);

        service.recordAbsence(pet, T0);

        assertThat(stored).hasSize(3);
        // save 3번 + trim 1번 = 조회 1번(정리) — 컷마다 정리하면 3번이 된다
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.times(1))
                .findByPetIdOrderBySceneAtDescIdDesc(any());
    }

    @Test
    @DisplayName("★★ 넷째 컷이 생기면 가장 오래된 것이 사라진다(보관 3개)")
    void fourthDropsOldest() {
        ZzalPet pet = child();
        for (int day = 0; day < 4; day++) {
            ReflectionTestUtils.setField(pet, "absenceAwakeSec",
                    ZzalRules.SCENE_ABSENCE_CHUNK.getSeconds());
            service.recordAbsence(pet, T0.plus(Duration.ofDays(day)));
        }
        assertThat(stored).hasSize(ZzalRules.SCENE_KEEP);
        assertThat(stored.stream().map(ZzalScene::getSceneAt))
                .doesNotContain(T0)                            // 첫날 것이 밀려났다
                .contains(T0.plus(Duration.ofDays(3)));
    }

    @Test
    @DisplayName("★★ 컷의 시각은 깨어 있던 순간이다 — 자는 새벽에 논 장면이 만들어지면 안 된다")
    void stampsFallInAwakeHours() {
        ZzalPet pet = child();
        ReflectionTestUtils.setField(pet, "absenceAwakeSec", 12 * 3600L);

        // 사흘을 비웠다가 아침 07:00 에 돌아왔다(그 시각은 아직 자는 시간대)
        service.recordAbsence(pet, kst("2026-09-09 07:00"));

        assertThat(stored).hasSize(3);
        for (ZzalScene scene : stored) {
            java.time.LocalTime t = scene.getSceneAt().atZone(ZzalRules.ZONE).toLocalTime();
            assertThat(t).as("컷 시각 %s 는 낮이어야 한다", scene.getSceneAt())
                    .isAfterOrEqualTo(ZzalRules.AUTO_WAKE_AT)
                    .isBeforeOrEqualTo(ZzalRules.AUTO_SLEEP_AT);
        }
    }

    @Test
    @DisplayName("★ 깨어 있는 시간으로 되짚기 — 낮 안이면 그대로 빼고, 밤을 만나면 전날 저녁으로 건너뛴다")
    void awakeMinusSkipsSleep() {
        // 15:00 에서 4시간 → 같은 날 11:00(낮 안)
        assertThat(SceneService.awakeMinus(kst("2026-09-08 15:00"), 4 * 3600))
                .isEqualTo(kst("2026-09-08 11:00"));
        // 11:00 에서 4시간 → 10:00 까지 1시간뿐이라 전날 23:00 에서 3시간 더 → 20:00
        assertThat(SceneService.awakeMinus(kst("2026-09-08 11:00"), 4 * 3600))
                .isEqualTo(kst("2026-09-07 20:00"));
        // 새벽 03:00 은 자는 시간 — 먼저 전날 23:00 으로 옮기고 거기서 뺀다
        assertThat(SceneService.awakeMinus(kst("2026-09-08 03:00"), 3600))
                .isEqualTo(kst("2026-09-07 22:00"));
    }

    @Test
    @DisplayName("★ 나머지 시간은 남는다 — 7시간을 비우면 한 컷을 받고 3시간이 남는다")
    void leftoverIsKept() {
        ZzalPet pet = child();
        ReflectionTestUtils.setField(pet, "absenceAwakeSec", 7 * 3600L);

        service.recordAbsence(pet, T0);

        assertThat(stored).hasSize(1);
        assertThat(pet.getAbsenceAwakeSec()).isEqualTo(3 * 3600L);
    }

    @Test
    @DisplayName("★★ 같은 시각·같은 펫이면 언제나 같은 장면 — 정산이 두 번 돌아도 그림이 안 바뀐다")
    void sameSeedSameScene() {
        ZzalPet pet = child();
        Instant at = kst("2026-09-06 14:00");
        for (int i = 0; i < 20; i++) {
            assertThat(service.idleMotion(pet, at)).isEqualTo(service.idleMotion(pet, at));
            assertThat(service.prop(pet, at)).isEqualTo(service.prop(pet, at));
        }
    }

    @Test
    @DisplayName("★ 소품은 하루에 하나 — 같은 날은 같은 소품, 날이 바뀌면 다시 뽑는다")
    void propIsPerDay() {
        ZzalPet pet = child();
        assertThat(service.prop(pet, kst("2026-09-06 10:00")))
                .isEqualTo(service.prop(pet, kst("2026-09-06 22:00")));
        assertThat(SceneService.PROPS).contains(service.prop(pet, kst("2026-09-07 10:00")));
    }

    @Test
    @DisplayName("★★ 밤 장면 판정은 <b>잠들 때</b>의 상태로 — 밤새 병이 나도 그 밤 장면은 남는다")
    void nightSceneUsesSleepTimeState() {
        ZzalPet pet = child();
        ReflectionTestUtils.setField(pet, "scenesEnabledAt", T0);
        pet.sleep(kst("2026-09-05 20:00"));           // 건강한 채로 잠들었다
        ReflectionTestUtils.setField(pet, "sickSince", kst("2026-09-06 02:00"));   // 밤새 병이 났다(주입)

        assertThat(service.recordNight(pet)).as("아침 상태로 판정하면 여기서 버려진다").isEqualTo(1);
        assertThat(stored).singleElement().satisfies(sc -> {
            assertThat(sc.isNight()).isTrue();
            assertThat(sc.getMood()).as("기분도 잠들 때 것이어야 한다").isNotEqualTo("SICK");
        });
    }

    @Test
    @DisplayName("★★ 반대도 마찬가지 — 아픈 채로 잠들었으면 아침에 나아 있어도 그 밤 장면은 없다")
    void sickAtSleepMeansNoScene() {
        ZzalPet pet = child();
        ReflectionTestUtils.setField(pet, "scenesEnabledAt", T0);
        ReflectionTestUtils.setField(pet, "sickSince", T0);
        pet.sleep(kst("2026-09-05 20:00"));           // 아픈 채로 잠들었다
        pet.medicine(kst("2026-09-06 08:00"));        // 아침에 나았다(주입)

        assertThat(service.recordNight(pet)).isZero();
        assertThat(stored).isEmpty();
    }

    @Test
    @DisplayName("★ 기능이 열리기 전(첫 부재 4시간 전)에는 밤 장면도 안 남는다")
    void noNightSceneBeforeEnabled() {
        ZzalPet pet = child();
        pet.sleep(kst("2026-09-05 20:00"));
        assertThat(pet.isScenesEnabled()).isFalse();

        assertThat(service.recordNight(pet)).isZero();
        assertThat(stored).isEmpty();
    }

    @Test
    @DisplayName("★★ 여행 중에는 장면이 안 남는다 — 방에 없기 때문(그때 이야기는 엽서가 맡는다)")
    void noScenesWhileTraveling() {
        ZzalPet pet = child();
        ReflectionTestUtils.setField(pet, "absenceAwakeSec", 12 * 3600L);
        ReflectionTestUtils.setField(pet, "tripStartedAt", T0);

        assertThat(service.recordAbsence(pet, T0)).isZero();
        assertThat(service.recordNight(pet)).isZero();
        assertThat(stored).isEmpty();
    }

    @Test
    @DisplayName("★★ 여행 중에는 부재 시계도 안 흐른다 — 돌아와서 장면이 몰아서 생기지 않는다")
    void absenceStopsWhileTraveling() {
        ZzalPet pet = child();
        ReflectionTestUtils.setField(pet, "absenceAwakeSec", 0L);   // 아기 60분에 쌓인 것을 지우고 시작
        ReflectionTestUtils.setField(pet, "tripStartedAt", T0);

        pet.settle(kst("2026-09-05 22:00"));                   // 깨어 있는 10시간

        assertThat(pet.getAbsenceAwakeSec()).isZero();
        assertThat(pet.pendingScenes()).isZero();
    }

    @Test
    @DisplayName("★ 밤에 잠들면 밤 장면 한 컷 — 같은 잠에 두 번은 안 남긴다")
    void nightSceneOncePerSleep() {
        ZzalPet pet = child();
        pet.consumeScenes(0, T0);
        ReflectionTestUtils.setField(pet, "scenesEnabledAt", T0);   // 기능이 열린 뒤부터 장면이 남는다
        pet.sleep(kst("2026-09-05 20:00"));
        assertThat(pet.getSleepKind()).isEqualTo(SleepKind.NIGHT);

        assertThat(service.recordNight(pet)).isEqualTo(1);
        assertThat(service.recordNight(pet)).isZero();         // 두 번째는 안 남는다
        assertThat(stored).singleElement().satisfies(s -> {
            assertThat(s.isNight()).isTrue();
            // ★ 옛 훈련 자세(practice)는 카탈로그에서 빠졌다 — 밤 컷도 기본 자세를 쓴다.
            assertThat(s.getMotionKey()).isEqualTo("base");
        });
    }

    @Test
    @DisplayName("★★ 아프면 밤 장면을 안 남긴다 — 아픈 아이가 밤에 혼자 노는 그림은 이야기가 안 맞는다")
    void noNightSceneWhileSick() {
        ZzalPet pet = child();
        ReflectionTestUtils.setField(pet, "scenesEnabledAt", T0);
        ReflectionTestUtils.setField(pet, "sickSince", T0);
        pet.sleep(kst("2026-09-05 20:00"));

        assertThat(service.recordNight(pet)).isZero();
        assertThat(stored).isEmpty();
        assertThat(service.recordNight(pet)).isZero();         // 표식은 찍혀서 매번 다시 보지 않는다
    }

    @Test
    @DisplayName("★ 대기 자세는 게이지 우선순위대로 — 병 > 배부름 0 > 행복 0 > 흔적")
    void idleFollowsMood() {
        ZzalPet pet = child();
        Instant at = kst("2026-09-05 14:00");

        assertThat(pet.mood()).isEqualTo(ZzalPet.Mood.NORMAL);

        ReflectionTestUtils.setField(pet, "happiness", 0);
        assertThat(service.idleMotion(pet, at)).isEqualTo("sad");

        ReflectionTestUtils.setField(pet, "fullness", 0);
        assertThat(service.idleMotion(pet, at)).isEqualTo("base");

        ReflectionTestUtils.setField(pet, "sickSince", T0);
        assertThat(service.idleMotion(pet, at)).isEqualTo("sick");
    }

    // ── 대기 자세 뽑기 60 / 40 (M-28) ────────────────────────────────────

    /** 낮 시각 여러 개에서 뽑은 자세들. 결정적이므로 시각을 바꾸는 것이 곧 다른 뽑기다. */
    private List<String> poolOverTheDay(ZzalPet pet, int samples) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < samples; i++) {
            out.add(service.idleMotion(pet, kst("2026-09-06 11:00").plusSeconds(i * 97L)));
        }
        return out;
    }

    /** 2층을 전부 연 펫 — 그래도 대기 자세는 하나도 없다(관상용이 3층으로 내려갔다). */
    private ZzalPet everythingUnlocked() {
        ZzalPet pet = child();
        ReflectionTestUtils.setField(pet, "feeds", 99);
        ReflectionTestUtils.setField(pet, "snacks", 99);
        ReflectionTestUtils.setField(pet, "cleans", 99);
        ReflectionTestUtils.setField(pet, "bathCount", 99);
        ReflectionTestUtils.setField(pet, "chatAnswers", 99);
        ReflectionTestUtils.setField(pet, "pets", 99);
        ReflectionTestUtils.setField(pet, "gameStarts", 99);
        ReflectionTestUtils.setField(pet, "wakes", 99);
        MotionCatalog catalog = new MotionCatalog("", "", "v1");
        assertThat(com.lore.zzal.pet.UnlockRules.openedLayerTwo(pet, catalog))
                .as("2층 여덟이 다 열린 상태여야 한다").isEqualTo(8);
        return pet;
    }

    @Test
    @DisplayName("★★ 정상일 때는 언제나 기본 자세 — 1·2층에 대기용 자세가 하나도 없다")
    void idlePoolIsAlwaysBase() {
        // ★ 앉아 쉬기·웃는 대기·갸웃은 사용자 행동에 붙지 않는 관상용이라 3층으로 내려갔다.
        //   그래서 2층을 다 연 사람도 정상 상태의 컷은 기본 자세뿐이다.
        assertThat(poolOverTheDay(everythingUnlocked(), 200)).containsOnly("base");
    }

    @Test
    @DisplayName("★ 하나도 안 열렸으면 언제나 기본 자세 — 부화 직후가 이 상태다")
    void withNothingUnlockedItIsAlwaysBase() {
        assertThat(poolOverTheDay(child(), 100)).containsOnly("base");
    }

    @Test
    @DisplayName("★ 게이지 우선순위가 먼저다 — 2층을 다 열어도 아프면 sick, 슬프면 sad")
    void gaugesWinOverTheIdlePool() {
        ZzalPet pet = everythingUnlocked();
        Instant at = kst("2026-09-06 14:00");

        ReflectionTestUtils.setField(pet, "trash", ZzalRules.TRASH_MAX);
        assertThat(service.idleMotion(pet, at)).as("흔적이 쌓이면 기본 자세에 소품이 붙는다").isEqualTo("base");

        ReflectionTestUtils.setField(pet, "happiness", 0);
        assertThat(service.idleMotion(pet, at)).isEqualTo("sad");

        ReflectionTestUtils.setField(pet, "sickSince", T0);
        assertThat(service.idleMotion(pet, at)).isEqualTo("sick");
    }

    // ── 되짚기의 경계 (M-29) ─────────────────────────────────────────────

    @Test
    @DisplayName("★★ 깨어 있던 마지막 순간 — 09:59:59 는 전날 23:00, 10:00 정각부터는 그 자리")
    void lastAwakeMomentAtTheMorningEdge() {
        assertThat(SceneService.lastAwakeMoment(kst("2026-09-08 09:59:59")))
                .as("10시 직전은 아직 자는 시간이라 전날 저녁으로 접힌다")
                .isEqualTo(kst("2026-09-07 23:00"));
        assertThat(SceneService.lastAwakeMoment(kst("2026-09-08 10:00:00")))
                .isEqualTo(kst("2026-09-08 10:00:00"));
        assertThat(SceneService.lastAwakeMoment(kst("2026-09-08 10:00:01")))
                .isEqualTo(kst("2026-09-08 10:00:01"));
    }

    @Test
    @DisplayName("★★ 22:59:59 는 그 자리, 23:00 부터는 그날 23:00 으로 접힌다 — 한밤중 컷을 막는 선이다")
    void lastAwakeMomentAtTheNightEdge() {
        assertThat(SceneService.lastAwakeMoment(kst("2026-09-08 22:59:59")))
                .isEqualTo(kst("2026-09-08 22:59:59"));
        assertThat(SceneService.lastAwakeMoment(kst("2026-09-08 23:00:00")))
                .isEqualTo(kst("2026-09-08 23:00"));
        assertThat(SceneService.lastAwakeMoment(kst("2026-09-08 23:00:01")))
                .as("자정 직전에 조회해도 컷은 23:00 이하로 찍힌다")
                .isEqualTo(kst("2026-09-08 23:00"));
    }

    @Test
    @DisplayName("★ 0초를 되짚으면 '지금 깨어 있던 순간' 그대로 — 밤이면 그 직전 23:00")
    void awakeMinusZero() {
        assertThat(SceneService.awakeMinus(kst("2026-09-08 15:00"), 0)).isEqualTo(kst("2026-09-08 15:00"));
        assertThat(SceneService.awakeMinus(kst("2026-09-08 03:00"), 0)).isEqualTo(kst("2026-09-07 23:00"));
        assertThat(SceneService.awakeMinus(kst("2026-09-08 23:30"), 0)).isEqualTo(kst("2026-09-08 23:00"));
    }

    @Test
    @DisplayName("★ 며칠치를 되짚어도 컷은 낮 안에만 — 하루 13시간씩 건너뛴다")
    void awakeMinusAcrossSeveralNights() {
        long dayLength = ZzalRules.AWAKE_PER_DAY.getSeconds();

        assertThat(SceneService.awakeMinus(kst("2026-09-08 23:00"), dayLength))
                .as("깨어 있는 하루치를 빼면 그날 아침 10:00")
                .isEqualTo(kst("2026-09-08 10:00"));
        assertThat(SceneService.awakeMinus(kst("2026-09-08 23:00"), dayLength * 2))
                .isEqualTo(kst("2026-09-07 10:00"));
        assertThat(SceneService.awakeMinus(kst("2026-09-08 23:00"), dayLength * 3))
                .isEqualTo(kst("2026-09-06 10:00"));
    }

    @Test
    @DisplayName("★ 문구에 사용자를 탓하는 말이 없다 — 오래 비웠어도 덤덤한 톤")
    void linesNeverBlameTheUser() {
        ZzalPet pet = child();
        ReflectionTestUtils.setField(pet, "absenceAwakeSec", 12 * 3600L);
        service.recordAbsence(pet, T0);

        for (ZzalScene scene : stored) {
            String line = SceneService.line(scene);
            assertThat(line).isNotBlank()
                    .doesNotContain("왜").doesNotContain("안 오").doesNotContain("혼자 두")
                    .doesNotContain("서운").doesNotContain("미워");
        }
    }
}
