package com.lore.zzal.night;

import com.lore.zzal.motion.MotionService;
import com.lore.zzal.motion.ZzalMotion;
import com.lore.zzal.motion.ZzalMotionRepository;
import com.lore.zzal.pet.AwakeClock;
import com.lore.zzal.pet.PetPhase;
import com.lore.zzal.pet.ZzalPet;
import com.lore.zzal.pet.ZzalPetRepository;
import com.lore.zzal.pet.ZzalRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.InetAddress;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 23:00 밤 스위프 — <b>유일한 {@code @Scheduled}</b>(플랜 T1 핵심 판단 2).
 *
 * <h3>★ 2026-10-26 시간대 2단계 — 15분마다, 시간대마다</h3>
 * 펫마다 시간대가 있어 "23:00" 이 시간대마다 다른 순간이다. 그래서 15분마다 깨어, 지금 밤 창(23:00~10:00)에 든
 * 시간대마다 그 시간대의 밤 한 판을 돈다. 시간대 오프셋은 전부 15분의 배수라(+5:30·+5:45 포함) 15분 눈금이면
 * 어느 시간대의 23:00 도 정각에 잡힌다. 판의 키는 {@code (night_of, time_zone)}, 펫 단위 멱등은
 * {@code zzal_pet.last_swept_night_of} — 같은 펫의 같은 밤(현지 날짜)은 한 번만 돈다.
 * 한국 펫(시간대 NULL 포함)은 지금까지처럼 KST 23:00 첫 눈금에 한 번 돈다. 굽기 상한 K 는 판마다가 아니라
 * <b>KST 하루</b>(10:00~다음 날 10:00)에 시작한 판들의 합으로 센다 — 하루 비용 상한이 시간대 수만큼 불지 않게.
 *
 * <h3>하는 일</h3>
 * <ol>
 *   <li>{@code zzal_night_run(night_of PK)} 를 먼저 꽂는다 — 못 꽂으면(이미 있음) 다른 서버가 돌렸거나 이미 돈 밤이다</li>
 *   <li>ALIVE 펫 전부 정산(23:00 자동 취침이 여기서 일어난다 — 안 연 사람 것도) → {@link NightPlanner#plan}</li>
 *   <li>QUEUED(이월 포함)를 우선순위(선물 > 케어 미스 0인 날 연속 > 친밀도 > id)로 K=200 까지 {@code claim} → 굽기</li>
 *   <li>나머지는 QUEUED 로 남아 다음 밤으로 이월</li>
 * </ol>
 *
 * <h3>★ 왜 시각 트리거가 여기 하나만인가</h3>
 * 게이지·잠은 조회 때 되짚는(settle) 구조라 타이머가 없다. 굽기만은 "안 연 사람 것도 23:00 에 등록" 돼야 해서
 * 시각 트리거가 필요하다(결정기록 B4·B19). 서버가 여러 대면 {@code app.zzal.night.sweep-enabled} 를 한 대만 켠다 —
 * 켜도 PK 와 claim 이 이중 굽기를 막지만, 애초에 한 대가 맞다.
 *
 * <h3>기동 복구</h3>
 * 서버가 23:00~10:00 사이에 뜨면 그 밤의 run 을 본다.
 * <ul>
 *   <li>run 이 <b>없으면</b> 스위프를 돈다(23:00 에 죽어 있었던 경우)</li>
 *   <li>run 이 있고 <b>안 끝났으면</b>(계획 도중 죽음) 계획을 다시 돌린 뒤 남은 QUEUED 를 집는다.
 *       {@link NightPlanner#plan} 은 멱등이라 두 번 돌아도 같은 행이 두 번 오르지 않는다 —
 *       계획 도중에 죽었을 때 <b>나머지 펫이 그 밤을 통째로 빠지는 것</b>을 막는다(2026-09-05 리뷰 중-2)</li>
 *   <li>run 이 <b>이미 끝났으면</b> 계획·기록은 다시 하지 않고, 남아 있는 QUEUED 만 집는다 —
 *       {@code StuckMotionRecovery} 가 회수한 자리가 여기로 온다</li>
 * </ul>
 * {@code BAKING} 인 채 죽은 자리는 {@code StuckMotionRecovery} 가 <b>QUEUED 로 되돌려</b> 이 길에 태운다.
 *
 * <h3>실행기</h3>
 * {@code hatchExecutor} 는 3스레드·큐 50·CallerRuns 라 200건을 넣으면 스케줄러 스레드가 굽기를 떠안고 부화와 자리를
 * 다툰다. 그래서 {@code nightExecutor}(2스레드·큐 = K)에 {@link MotionService#bakeNow} 를 넣는다.
 */
@Component
public class NightSweep {

    private static final Logger log = LoggerFactory.getLogger(NightSweep.class);

    /**
     * 기동 복구 순서 — {@code StuckMotionRecovery}({@value com.lore.zzal.motion.StuckMotionRecovery#RECOVERY_ORDER})
     * 가 멈춘 자리를 큐로 되돌린 <b>뒤에</b> 돈다. 그래야 회수한 것이 그 자리에서 다시 집힌다.
     */
    static final int RECOVERY_ORDER = com.lore.zzal.motion.StuckMotionRecovery.RECOVERY_ORDER + 10;

    private final ZzalPetRepository petRepository;
    private final ZzalMotionRepository motionRepository;
    private final ZzalNightRunRepository runRepository;
    private final NightPlanner planner;
    private final MotionService motionService;
    private final Executor nightExecutor;
    private final TransactionTemplate tx;
    private final boolean enabled;
    private final int maxBakes;
    private final String server;

    public NightSweep(ZzalPetRepository petRepository,
                      ZzalMotionRepository motionRepository,
                      ZzalNightRunRepository runRepository,
                      NightPlanner planner,
                      MotionService motionService,
                      @Qualifier("nightExecutor") Executor nightExecutor,
                      TransactionTemplate tx,
                      @Value("${app.zzal.night.sweep-enabled:false}") boolean enabled,
                      @Value("${app.zzal.night.max-bakes:200}") int maxBakes) {
        this.petRepository = petRepository;
        this.motionRepository = motionRepository;
        this.runRepository = runRepository;
        this.planner = planner;
        this.motionService = motionService;
        this.nightExecutor = nightExecutor;
        this.tx = tx;
        this.enabled = enabled;
        this.maxBakes = maxBakes;
        this.server = hostname();
    }

    /** 스케줄 눈금이 본 덜 끝난 판을 "다른 곳에서 도는 중" 으로 보고 물러나는 시간. 이보다 오래됐으면 죽은 흔적이다. */
    static final java.time.Duration STALE_RUN = java.time.Duration.ofMinutes(30);

    /** 결과 — 이 밤에 새로 올린 수·집은 수·이월 수. dev 응답·로그용. */
    public record Result(LocalDate nightOf, boolean ran, int queued, int claimed, int carried) {
        static Result skipped(LocalDate nightOf) {
            return new Result(nightOf, false, 0, 0, 0);
        }
    }

    // ── 트리거 ────────────────────────────────────────────────────────────

    /**
     * 15분 눈금. 지금 밤 창(23:00~10:00)에 든 시간대마다 그 밤의 판을 돈다.
     * 이미 끝난 판이면 아무것도 안 한다(옛 하루 한 번 스위프와 같다 — 남은 큐 줍기는 기동 복구만 한다).
     */
    @Scheduled(cron = "0 */15 * * * *", zone = "Asia/Seoul")
    public void sweep() {
        if (!enabled) {
            return;
        }
        tick(Instant.now(), "scheduled");
    }

    /**
     * 기동 복구 — 밤 창 안의 시간대마다, 그 밤의 판이 없거나 안 끝났으면 이어서, 끝났으면 남은 큐만.
     * 스위프가 꺼진 서버는 아무것도 하지 않는다(다른 서버가 돈다).
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(RECOVERY_ORDER)
    public void recoverOnBoot() {
        if (!enabled) {
            return;
        }
        tick(Instant.now(), "boot-recovery");
    }

    /** 한 눈금 — 살아 있는 펫들의 시간대(+ 늘 서울) 중 지금 밤 창에 든 것마다 {@link #run}. */
    List<Result> tick(Instant now, String trigger) {
        List<Result> out = new java.util.ArrayList<>();
        for (java.time.ZoneId zone : zonesToVisit()) {
            LocalDate night = nightWindowOf(now, zone);
            if (night != null) {
                out.add(run(night, zone, now, trigger));
            }
        }
        return out;
    }

    /**
     * 돌 시간대 — 살아 있는 펫의 시간대 전부, 그리고 <b>늘 서울</b>.
     * ★ 서울을 늘 넣는 이유 — 옛 스위프는 펫이 없어도 KST 23:00 에 판을 열어 이월된 큐를 집었다. 그 리듬을 그대로 둔다.
     */
    java.util.Set<java.time.ZoneId> zonesToVisit() {
        java.util.Set<java.time.ZoneId> zones = new java.util.LinkedHashSet<>();
        zones.add(ZzalRules.ZONE);
        for (ZzalPet p : petRepository.findByPhase(PetPhase.ALIVE)) {
            zones.add(p.zone());
        }
        return zones;
    }

    // ── 본체 ──────────────────────────────────────────────────────────────

    /** 서울의 한 밤 — 옛 호출처·시험용. */
    public Result run(LocalDate nightOf, Instant now, String trigger) {
        return run(nightOf, ZzalRules.ZONE, now, trigger);
    }

    /**
     * 한 시간대의 한 밤을 돈다. 두 번 불러도 같은 밤을 두 번 계획하지 않는다(run PK + 펫 단위 last_swept_night_of).
     */
    public Result run(LocalDate nightOf, java.time.ZoneId zone, Instant now, String trigger) {
        ZzalNightRun.Key key = new ZzalNightRun.Key(nightOf, zone.getId());
        ZzalNightRun run = runRepository.findById(key).orElse(null);
        boolean boot = "boot-recovery".equals(trigger);
        if (run != null && run.isFinished()) {
            // ★ 15분 눈금이 끝난 판을 볼 때마다 남은 큐를 집으면 K 가 눈금마다 다시 찬다(하루 상한이 무너진다).
            //   남은 큐 줍기는 옛 스위프처럼 기동 복구에서만 한다.
            return boot ? claimLeftovers(key, now, trigger) : Result.skipped(nightOf);
        }
        if (run != null && !boot && run.getStartedAt().isAfter(now.minus(STALE_RUN))) {
            // 막 시작한 판 — 다른 스레드(기동 복구)나 다른 서버가 도는 중이다. 겹쳐 돌면 상한 계산이 둘이 된다.
            return Result.skipped(nightOf);
        }
        if (run == null && !startRun(key, now)) {
            log.info("밤 스위프 건너뜀 — nightOf={} zone={} 이미 다른 곳이 돌렸다(run PK). trigger={}", nightOf, zone, trigger);
            return Result.skipped(nightOf);
        }
        if (run != null) {
            log.warn("밤 스위프 이어서 — nightOf={} zone={} 도중에 멈춘 run 이 있다(남은 펫만 계획한다). trigger={}",
                    nightOf, zone, trigger);
        }

        // ★ 이어받기에서도 계획을 돈다. 계획 도중에 죽었으면 뒤쪽 펫들은 아직 큐에 오르지 못했고,
        //   건너뛰면 그 펫들은 그 밤을 통째로 빠진다(리뷰 중-2). 이미 돈 펫은 last_swept_night_of 가 건너뛴다.
        int queued = planAll(nightOf, zone, now);
        int[] counts = claimAndBake(now, budget(now));
        finishRun(key, now, queued, counts[0], counts[1]);
        log.info("밤 스위프 끝 — nightOf={} zone={} 등록={} 집기={} 이월={} trigger={}",
                nightOf, zone, queued, counts[0], counts[1], trigger);
        return new Result(nightOf, true, queued, counts[0], counts[1]);
    }

    /**
     * 이번 판이 집을 수 있는 수 — K 에서 <b>같은 KST 하루</b>(10:00~다음 날 10:00)에 시작한 판들이 이미 집은 수를 뺀다.
     * 한국 펫만 있으면 하루에 판이 하나라 옛날과 같이 K 다.
     */
    int budget(Instant now) {
        LocalDate day = nightOf(now);
        Instant from = day.atTime(ZzalRules.AUTO_WAKE_AT).atZone(ZzalRules.ZONE).toInstant();
        Instant to = day.plusDays(1).atTime(ZzalRules.AUTO_WAKE_AT).atZone(ZzalRules.ZONE).toInstant();
        long used = runRepository.sumClaimedStartedBetween(from, to);
        return (int) Math.max(0, maxBakes - used);
    }

    /**
     * 이미 끝난 밤에 남아 있는 QUEUED 만 집는다 — 계획도 run 기록도 다시 하지 않는다.
     *
     * ★ 왜 그냥 건너뛰지 않나 — {@code finishedAt} 은 "굽기가 다 끝났다" 가 아니라 <b>"집어서 실행기에 넘기는 일이 끝났다"</b>
     *   이다(굽기는 밤새 돈다). 그래서 끝난 run 뒤에도 회수된 자리({@code StuckMotionRecovery} 가 되돌린 것)가 생길 수 있고,
     *   그때 아무도 안 집으면 그 행은 다음 밤까지 그대로 기다린다(리뷰 중-3).
     */
    private Result claimLeftovers(ZzalNightRun.Key key, Instant now, String trigger) {
        LocalDate nightOf = key.nightOf();
        int[] counts = claimAndBake(now, maxBakes);
        if (counts[0] == 0) {
            log.info("밤 스위프 건너뜀 — nightOf={} 이미 끝난 밤이고 남은 큐도 없다. trigger={}", nightOf, trigger);
            return Result.skipped(nightOf);
        }
        tx.execute(status -> {
            runRepository.findById(key).ifPresent(r -> r.addClaimed(counts[0]));
            return null;
        });
        log.warn("끝난 밤의 남은 큐를 집었다 — nightOf={} 집기={} 이월={} trigger={}", nightOf, counts[0], counts[1], trigger);
        return new Result(nightOf, true, 0, counts[0], counts[1]);
    }

    /**
     * dev — 이 펫만 지금 계획·굽기. run 기록은 남기지 않는다(진짜 밤이 아니다).
     *
     * ⚠️ <b>개발 확인 전용이다</b>({@code app.zzal.dev-tools} 가 켜져야 부를 수 있고, 운영에서는 주소 자체가 없다).
     *    K 상한·우선순위를 보지 않고 이 펫의 QUEUED 를 전부 집는다 — 한 펫의 큐는 많아야 몇 건이라 그게 편하지만,
     *    이월분까지 집어 가므로 <b>진짜 밤의 통계·상한과는 별개</b>다. 운영 판정은 언제나 {@link #run} 쪽이다.
     */
    public Result sweepPet(ZzalPet pet, Instant petNow) {
        LocalDate nightOf = AwakeClock.dateOf(petNow, pet.zone());
        int queued = planner.plan(pet, nightOf, NightPlanner.Occasion.NIGHT);
        List<ZzalMotion> mine = motionRepository.findByPetIdAndStatus(pet.getId(), com.lore.zzal.motion.MotionStatus.QUEUED);
        int claimed = 0;
        for (ZzalMotion m : mine) {
            if (claimOne(m.getId(), petNow)) {
                claimed++;
            }
        }
        return new Result(nightOf, true, queued, claimed, 0);
    }

    // ── 단계 ──────────────────────────────────────────────────────────────

    /** run 행을 꽂는다. PK 충돌이면 false — 다른 서버가 먼저 꽂았다. */
    boolean startRun(ZzalNightRun.Key key, Instant now) {
        try {
            tx.execute(status -> runRepository.saveAndFlush(ZzalNightRun.start(key.nightOf(), key.timeZone(), now, server)));
            return true;
        } catch (DataIntegrityViolationException e) {
            return false;
        }
    }

    void finishRun(ZzalNightRun.Key key, Instant now, int queued, int claimed, int carried) {
        tx.execute(status -> {
            runRepository.findById(key).ifPresent(r -> r.finish(now, queued, claimed, carried));
            return null;
        });
    }

    /** 서울의 ALIVE 펫 전부 — 옛 호출처·시험용. */
    int planAll(LocalDate nightOf, Instant now) {
        return planAll(nightOf, ZzalRules.ZONE, now);
    }

    /**
     * 그 시간대의 ALIVE 펫 전부 — 정산(23:00 자동 취침 포함) 뒤 계획. 펫마다 트랜잭션 하나(한 펫이 실패해도 다른 펫은 간다).
     *
     * ★★ 펫 단위 멱등 — {@code last_swept_night_of} 가 이 밤 이상이면 건너뛴다. 잠근 <b>뒤에</b> 다시 본다
     *   (두 스레드가 같은 펫을 동시에 집어도 한 번만 돈다). 시간대를 막 관측한 펫(예: KST 밤을 돈 뒤 미 서부가 된 펫)이
     *   같은 날짜의 현지 밤을 또 도는 것도 이것이 막는다.
     */
    int planAll(LocalDate nightOf, java.time.ZoneId zone, Instant now) {
        List<Long> ids = petRepository.findByPhase(PetPhase.ALIVE).stream()
                .filter(p -> p.zone().equals(zone) && !p.sweptNight(nightOf))
                .map(ZzalPet::getId).toList();
        int queued = 0;
        for (Long id : ids) {
            try {
                queued += tx.execute(status -> petRepository.findByIdForUpdate(id).map(pet -> {
                    if (!pet.zone().equals(zone) || pet.sweptNight(nightOf)) {
                        return 0;
                    }
                    pet.settle(pet.now(now));
                    int q = planner.plan(pet, nightOf, NightPlanner.Occasion.NIGHT);
                    pet.markSwept(nightOf);
                    return q;
                }).orElse(0));
            } catch (RuntimeException e) {
                log.error("밤 계획 실패 — petId={} (다음 펫으로)", id, e);
            }
        }
        return queued;
    }

    /**
     * 우선순위로 K 까지 집어서 굽는다. 나머지는 QUEUED 로 남는다(이월).
     *
     * @return {집은 수, 이월 수}
     */
    int[] claimAndBake(Instant now, int cap) {
        List<ZzalMotion> queued = planner.queued();
        Map<Long, ZzalPet> pets = petRepository.findAllById(queued.stream().map(ZzalMotion::getPetId).distinct().toList())
                .stream().collect(Collectors.toMap(ZzalPet::getId, Function.identity()));
        List<ZzalMotion> ordered = queued.stream()
                .filter(m -> !com.lore.zzal.motion.RetiredRewardGeneration.isRetiredNewMotion(m.getSeq()))
                .sorted(priority(pets))
                .toList();
        int claimed = 0;
        int carried = 0;
        for (ZzalMotion m : ordered) {
            // ★ 이월 = "상한에 걸려 손도 안 댄 것" 만 센다. 집기에 진 것(다른 서버가 먼저 가져감)은
            //   우리 이월이 아니다 — 그것까지 세면 두 대가 돌 때 run 통계가 실제 큐와 어긋난다(리뷰 하).
            if (claimed >= cap) {
                carried++;
                continue;
            }
            if (claimOne(m.getId(), now)) {
                claimed++;
            }
        }
        return new int[]{claimed, carried};
    }

    /**
     * 집기 — UPDATE … WHERE status='QUEUED' 가 1 을 돌려줄 때만 굽는다. 0 이면 다른 서버가 먼저 집었다.
     *
     * ★ 실행기가 안 받으면 집기를 <b>되돌린다.</b> 종료 중인 실행기는 작업을 받지 않는데, 그때 되돌리지 않으면
     *   그 행은 아무도 굽지 않는 채 {@code BAKING} 으로 남는다(리뷰 Codex 4 — JDK 기본 CallerRunsPolicy 는
     *   종료 중이면 <b>조용히 버린다</b>. 그래서 {@code ZzalSchedulingConfig} 가 그 경우 예외를 던지게 바꿨다).
     */
    boolean claimOne(Long motionId, Instant now) {
        int won = motionRepository.claim(motionId, now, server);
        if (won != 1) {
            return false;
        }
        try {
            nightExecutor.execute(() -> motionService.bakeNow(motionId));
        } catch (RejectedExecutionException e) {
            int back = motionRepository.releaseClaim(motionId);
            log.error("실행기가 굽기를 받지 못했다 — motionId={} 집기를 되돌린다(되돌림={})", motionId, back, e);
            return false;
        }
        return true;
    }

    /**
     * 선물 > <b>오래된 밤 먼저</b> > 케어 미스 0인 날 수(3층 streak 의 자리) > 친밀도 > id. 펫이 없으면 맨 뒤.
     *
     * ★ {@code nightOf} 오름차순이 없으면 <b>이월분이 영영 안 구워진다</b>(리뷰 중-1 — 굶주림).
     *   매일 새로 오르는 건이 K 를 넘기면, 어제 밀린 낮은 우선순위는 오늘도 뒤로 밀리고 그게 반복된다.
     *   "어제 못 받은 사람 먼저" 가 사용자에게도 맞다.
     */
    static Comparator<ZzalMotion> priority(Map<Long, ZzalPet> pets) {
        Comparator<ZzalMotion> gift = Comparator.comparing((ZzalMotion m) -> m.getSeq() >= 101 ? 0 : 1);
        // nightOf 가 비어 있는 행(손으로 넣은 것)은 맨 뒤로.
        Comparator<ZzalMotion> oldestNight = Comparator.comparing(
                (ZzalMotion m) -> m.getNightOf() == null ? LocalDate.MAX : m.getNightOf());
        Comparator<ZzalMotion> streak = Comparator.comparing((ZzalMotion m) ->
                pets.containsKey(m.getPetId()) ? -pets.get(m.getPetId()).getZeroMissDays() : Integer.MAX_VALUE);
        Comparator<ZzalMotion> intimacy = Comparator.comparing((ZzalMotion m) ->
                pets.containsKey(m.getPetId()) ? -pets.get(m.getPetId()).getIntimacy() : Integer.MAX_VALUE);
        return gift.thenComparing(oldestNight).thenComparing(streak).thenComparing(intimacy).thenComparing(ZzalMotion::getId);
    }

    // ── 시각 ──────────────────────────────────────────────────────────────

    /**
     * 이 순간이 속한 밤(23:00 이 속한 KST 날짜). 23:00 정각에 부르면 오늘. 운영(경보·상한)용 — KST 고정.
     *
     * ★ 경보({@code ZzalAlerts.nightBakeFailed})도 이 자를 쓴다 — 새벽 1시의 실패는 <b>어제 밤</b>의
     *   실패다. 거기서 날짜를 따로 계산하면 같은 규칙이 두 벌이 되고, 둘이 어긋나도 아무 소리가 안 난다.
     */
    public static LocalDate nightOf(Instant now) {
        ZonedDateTime z = now.atZone(ZzalRules.ZONE);
        return z.toLocalTime().isBefore(ZzalRules.AUTO_WAKE_AT) ? z.toLocalDate().minusDays(1) : z.toLocalDate();
    }

    /** 23:00~10:00 창 안이면 그 밤(KST), 아니면 null. */
    static LocalDate nightWindowOf(Instant now) {
        return nightWindowOf(now, ZzalRules.ZONE);
    }

    /** 그 시간대의 23:00~10:00 창 안이면 그 밤(그 시간대의 날짜), 아니면 null. */
    static LocalDate nightWindowOf(Instant now, java.time.ZoneId zone) {
        ZonedDateTime z = now.atZone(zone);
        LocalTime t = z.toLocalTime();
        if (!t.isBefore(ZzalRules.AUTO_SLEEP_AT)) {
            return z.toLocalDate();
        }
        if (t.isBefore(ZzalRules.AUTO_WAKE_AT)) {
            return z.toLocalDate().minusDays(1);
        }
        return null;
    }

    private static String hostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
