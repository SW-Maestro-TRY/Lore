package com.lore.zzal.scene;

import com.lore.zzal.chat.BanFilter;
import com.lore.zzal.motion.MotionCatalog;
import com.lore.zzal.motion.MotionSpec;
import com.lore.zzal.pet.Chance;
import com.lore.zzal.pet.UnlockRules;
import com.lore.zzal.pet.ZzalPet;
import com.lore.zzal.pet.ZzalRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 혼자 논 장면 — 사용자가 없는 동안 무슨 일이 있었는지 <b>레시피로</b> 남긴다(정본 11·16장).
 *
 * <h3>★ 왜 "무슨 일" 을 지어내나</h3>
 * 부재는 벌이 아니라 <b>이야기</b>여야 한다(정본 0장의 방향). 안 왔다고 혼내는 대신,
 * "그동안 혼자 이렇게 있었어요" 를 보여준다. 그래서 이 서비스에는 사용자를 탓하는 말이 한 줄도 없다 —
 * 방치가 길었으면 오히려 <b>덤덤한 톤</b>("별일 없었어요")으로 내려간다.
 *
 * <h3>언제 남기나</h3>
 * <ul>
 *   <li><b>부재 4시간마다 한 컷</b> — 깨어 있는 시간 기준(자는 동안은 안 센다). 조회할 때 몰아서 계산한다</li>
 *   <li><b>밤에 잠들 때 한 컷</b> — 훈련 자세의 "연습 장면"(정본 2장). 아프면 안 남긴다(16장)</li>
 * </ul>
 *
 * <h3>★★ 뽑기는 결정적이다</h3>
 * 같은 시각·같은 펫이면 언제나 같은 장면이 나온다({@link Chance}, 결정기록 B65). 정산이 같은 구간을
 * 여러 번 걸어도 장면이 달라지지 않아야 하고, 그래야 "그때 왜 그 장면이었나" 를 나중에 되짚을 수 있다.
 */
@Service
public class SceneService {

    private static final Logger log = LoggerFactory.getLogger(SceneService.class);

    /** 정본 13장 공통 에셋의 소품 4종. 하루에 하나만 뽑힌다(정본 11장 3). */
    static final List<String> PROPS = List.of("ball", "book", "cup", "plant");

    /** 정상일 때의 대기 풀 — 기본 자세 60% / 앉아 쉬기·웃는 대기 40%(정본 11장 2). */
    private static final int IDLE_BASE_PERCENT = ZzalRules.IDLE_BASE_PERCENT;

    private final ZzalSceneRepository sceneRepository;
    private final MotionCatalog catalog;

    public SceneService(ZzalSceneRepository sceneRepository, MotionCatalog catalog) {
        this.sceneRepository = sceneRepository;
        this.catalog = catalog;
    }

    /**
     * 조회·행동 때 부재분을 정산한다 — 쌓인 4시간 청크만큼 장면을 남기고 그만큼 덜어낸다.
     *
     * @return 이번에 새로 남은 컷 수(0 이면 아무 일도 없었다)
     */
    public int recordAbsence(ZzalPet pet, Instant now) {
        if (!pet.isAlive() || pet.isTraveling()) {
            return 0;
        }
        int pending = pet.pendingScenes();
        if (pending <= 0) {
            return 0;
        }
        // ★ 몇 시간을 비웠든 남는 것은 최대 3컷이다. 열흘 만에 와서 60컷을 만들면 만들자마자 57컷을 지운다.
        int made = Math.min(pending, ZzalRules.SCENE_KEEP);
        for (int i = made; i >= 1; i--) {
            // 마지막 컷이 "가장 최근" 이 되도록 뒤에서부터 되짚는다. ★ 벽시계가 아니라 <b>깨어 있는 시간</b>으로 —
            //   그냥 4시간씩 빼면 자는 새벽 3시에 논 장면이 만들어지고, 화면은 그 시각으로 빛을 정한다.
            Instant at = awakeMinus(now, ZzalRules.SCENE_ABSENCE_CHUNK.getSeconds() * (i - 1L));
            save(pet, at, false);
        }
        pet.consumeScenes(pending, now);
        log.debug("혼자 논 장면 — petId={} 청크={} 저장={}", pet.getId(), pending, made);
        return made;
    }

    /**
     * 밤에 잠든 순간의 연습 장면(정본 2장 "잠드는 순간 하는 일 … 밤 장면").
     *
     * ★ 아프면 안 남긴다 — 정본 16장이 "아픈 동안 거부하는 것 = … 훈련 자세(연습 장면)" 라고 못 박았다.
     *   아픈 아이가 밤새 연습하는 그림은 규칙 이전에 이야기가 안 맞는다.
     *
     * @return 남겼으면 1, 아니면 0
     */
    public int recordNight(ZzalPet pet) {
        if (!pet.isAlive() || pet.isTraveling() || !pet.needsNightScene()) {
            return 0;
        }
        // ★ 기능이 아직 안 열렸으면 밤 장면도 없다 — 정본 16장은 "첫 부재 4시간이 지나면 기능이 켜지고
        //   <b>그 뒤부터</b> 장면이 남는다" 다. 여기서 막지 않으면 부화 첫날 밤에 장면 하나가 생기는데,
        //   화면에는 기능이 꺼져 있어(`features.scenes=false`) 보여줄 자리가 없다(실기동에서 실제로 그랬다).
        if (!pet.isScenesEnabled()) {
            return 0;
        }
        Instant at = pet.getSleptAt();
        pet.markNightScene(at);         // 아파서 안 남기더라도 표식은 찍는다(같은 잠을 매번 다시 보지 않게)
        if (pet.isSick()) {
            return 0;
        }
        save(pet, at, true);
        return 1;
    }

    /**
     * {@code at} 에서 <b>깨어 있는 시간</b>으로 {@code seconds} 만큼 되돌아간 시각. 자는 구간(23:00~10:00)은 건너뛴다.
     *
     * ★ 왜 필요한가 — 사흘 만에 아침 7시에 돌아온 사람에게 벽시계로 4시간씩 빼면 컷이 03:00·23:00 에 찍힌다.
     *   그 시각은 <b>자고 있던 시각</b>이고, 화면은 시각으로 빛을 정하므로(정본 11장 1) 한밤중 조명의
     *   "혼자 논 장면" 이 만들어진다. 컷 수는 맞는데 그림이 거짓말을 하는 종류다.
     *
     * ★ 자동 창(10:00~23:00)을 기준으로 삼는다. 실제로 언제 자고 깼는지는 그 펫의 이력이라 여기서 알 수 없지만,
     *   이 창 밖이면 <b>확실히 자고 있었다</b>. 근사치이되 "밤에 논 그림" 은 확실히 막는다.
     */
    static Instant awakeMinus(Instant at, long seconds) {
        Instant cur = lastAwakeMoment(at);
        while (seconds > 0) {
            Instant dayStart = cur.atZone(ZzalRules.ZONE).toLocalDate()
                    .atTime(ZzalRules.AUTO_WAKE_AT).atZone(ZzalRules.ZONE).toInstant();
            long available = Duration.between(dayStart, cur).getSeconds();
            if (available >= seconds) {
                return cur.minusSeconds(seconds);
            }
            seconds -= available;
            cur = lastAwakeMoment(dayStart.minusSeconds(1));    // 전날 밤의 마지막 깨어 있던 순간으로
        }
        return cur;
    }

    /** 그 순간이 이미 낮이면 그대로, 밤이면 <b>그 직전의 깨어 있던 순간</b>(그날 23:00 또는 전날 23:00). */
    static Instant lastAwakeMoment(Instant at) {
        java.time.ZonedDateTime z = at.atZone(ZzalRules.ZONE);
        java.time.LocalTime t = z.toLocalTime();
        if (t.isBefore(ZzalRules.AUTO_WAKE_AT)) {
            return z.toLocalDate().minusDays(1).atTime(ZzalRules.AUTO_SLEEP_AT).atZone(ZzalRules.ZONE).toInstant();
        }
        if (!t.isBefore(ZzalRules.AUTO_SLEEP_AT)) {
            return z.toLocalDate().atTime(ZzalRules.AUTO_SLEEP_AT).atZone(ZzalRules.ZONE).toInstant();
        }
        return at;
    }

    /** 그 펫의 장면, 최근 것부터(최대 3). */
    public List<ZzalScene> recent(Long petId) {
        return sceneRepository.findByPetIdOrderBySceneAtDescIdDesc(petId);
    }

    // ── 한 컷 만들기 ──────────────────────────────────────────────────────

    private void save(ZzalPet pet, Instant at, boolean night) {
        String motionKey = night ? "practice" : idleMotion(pet, at);
        sceneRepository.save(ZzalScene.of(pet.getId(), motionKey, pet.getBackground(),
                prop(pet, at), at, pet.mood().name(), night));
        trim(pet.getId());
    }

    /**
     * 넘치는 것을 지운다 — <b>가장 오래된 것부터</b>(정본 16장 "보관 3개").
     *
     * ★ 지우지 않으면 이 표가 사용자당 무한히 자란다. 지우는 쪽을 고른 이유는 "3개" 가 정본이고,
     *   더 남겨 두면 앨범에서 어느 셋을 보여줄지 또 정해야 하기 때문이다.
     */
    private void trim(Long petId) {
        List<ZzalScene> all = sceneRepository.findByPetIdOrderBySceneAtDescIdDesc(petId);
        if (all.size() <= ZzalRules.SCENE_KEEP) {
            return;
        }
        all.subList(ZzalRules.SCENE_KEEP, all.size()).forEach(sceneRepository::delete);
    }

    /**
     * 그때 무슨 자세였나 — 게이지 우선순위가 먼저, 정상이면 대기 풀에서 뽑는다(정본 11장 2·4장).
     *
     * <pre>
     *   병      → 아픈 자세      배부름 0 → 기본 자세(꼬르륵 에셋이 붙는다)
     *   행복 0  → 슬픈 자세      흔적 3+  → 기본 자세(파리·쓰레기가 붙는다)
     *   정상    → 기본 자세 60% / 앉아 쉬기·웃는 대기 40%(열린 것만)
     * </pre>
     */
    String idleMotion(ZzalPet pet, Instant at) {
        return switch (pet.mood()) {
            case SICK -> "sick";
            case SAD -> "sad";
            case HUNGRY, DIRTY -> "base";
            case NORMAL -> idlePool(pet, at);
        };
    }

    private String idlePool(ZzalPet pet, Instant at) {
        List<String> relaxed = List.of("sit", "smile_idle").stream()
                .filter(key -> catalog.byKey(key).map(spec -> UnlockRules.isUnlocked(pet, spec, catalog)).orElse(false))
                .toList();
        if (relaxed.isEmpty() || Chance.percent("scene-idle", pet.chanceSeed(), at.getEpochSecond()) < IDLE_BASE_PERCENT) {
            return "base";
        }
        int pick = (int) Chance.pick(relaxed.size(), "scene-relaxed", pet.chanceSeed(), at.getEpochSecond());
        return relaxed.get(pick);
    }

    /**
     * 그날의 소품 — <b>하루에 하나</b>(정본 11장 3). 날짜가 씨앗이라 같은 날은 같은 소품이 나온다.
     *
     * ★ 컷마다 다시 뽑으면 "아침에 하나 뽑아 하루 종일" 이라는 규칙이 깨지고, 네 시간마다 방에 다른 물건이
     *   놓인 그림이 된다.
     */
    String prop(ZzalPet pet, Instant at) {
        long day = at.atZone(ZzalRules.ZONE).toLocalDate().toEpochDay();
        return PROPS.get((int) Chance.pick(PROPS.size(), "scene-prop", pet.chanceSeed(), day));
    }

    /**
     * 그 장면에 붙는 한 줄 — <b>저장하지 않고 그때그때 만든다</b>(문구는 계속 다듬으므로).
     *
     * ★★ 사용자를 탓하는 말은 한 줄도 없다. 오래 비웠을수록 원망이 아니라 <b>덤덤한 톤</b>으로 간다
     *   (자캐 커뮤니티 규범 — "캐릭터가 사용자를 원망하는 대사 전면 금지"). 마지막에 금지 필터도 한 번 더 지난다.
     */
    public static String line(ZzalScene scene) {
        String raw = scene.isNight() ? "자기 전에 혼자 연습했어요."
                : switch (scene.getMood()) {
            case "SICK" -> "혼자 조용히 쉬고 있었어요.";
            case "HUNGRY" -> "창밖을 보며 기다렸어요.";
            case "SAD" -> "구석에서 조용히 있었어요.";
            case "DIRTY" -> "방을 어슬렁거렸어요.";
            default -> "혼자서도 잘 놀았어요.";
        };
        return BanFilter.clean(raw);
    }

    /** 카탈로그에 없는 key 가 저장돼 있으면(옛 행) 그대로 둔다 — 화면이 폴백을 쓴다. */
    String label(String motionKey) {
        return catalog.byKey(motionKey).map(MotionSpec::label).orElse(motionKey);
    }
}
