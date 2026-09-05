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
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 23:00 밤 스위프 — <b>유일한 {@code @Scheduled}</b>(플랜 T1 핵심 판단 2).
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
 * 서버가 23:00~10:00 사이에 뜨면 그 밤의 run 을 본다. 없으면 스위프를 돈다(23:00 에 죽어 있었던 경우).
 * 있는데 안 끝났으면(스위프 도중 죽음) <b>계획은 다시 하지 않고</b> 남은 QUEUED 만 이어서 집는다.
 *
 * <h3>실행기</h3>
 * {@code hatchExecutor} 는 3스레드·큐 50·CallerRuns 라 200건을 넣으면 스케줄러 스레드가 굽기를 떠안고 부화와 자리를
 * 다툰다. 그래서 {@code nightExecutor}(2스레드·큐 = K)에 {@link MotionService#bakeNow} 를 넣는다.
 */
@Component
public class NightSweep {

    private static final Logger log = LoggerFactory.getLogger(NightSweep.class);

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

    /** 결과 — 이 밤에 새로 올린 수·집은 수·이월 수. dev 응답·로그용. */
    public record Result(LocalDate nightOf, boolean ran, int queued, int claimed, int carried) {
        static Result skipped(LocalDate nightOf) {
            return new Result(nightOf, false, 0, 0, 0);
        }
    }

    // ── 트리거 ────────────────────────────────────────────────────────────

    @Scheduled(cron = "0 0 23 * * *", zone = "Asia/Seoul")
    public void sweep() {
        if (!enabled) {
            return;
        }
        Instant now = Instant.now();
        run(nightOf(now), now, "scheduled");
    }

    /**
     * 기동 복구 — 23:00~10:00 사이에 떴고 그 밤의 run 이 없거나 안 끝났으면 이어서.
     * 스위프가 꺼진 서버는 아무것도 하지 않는다(다른 서버가 돈다).
     */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverOnBoot() {
        if (!enabled) {
            return;
        }
        Instant now = Instant.now();
        LocalDate night = nightWindowOf(now);
        if (night == null) {
            return;
        }
        run(night, now, "boot-recovery");
    }

    // ── 본체 ──────────────────────────────────────────────────────────────

    /**
     * 한 밤을 돈다. 두 번 불러도 같은 밤을 두 번 계획하지 않는다(run PK).
     */
    public Result run(LocalDate nightOf, Instant now, String trigger) {
        ZzalNightRun run = runRepository.findById(nightOf).orElse(null);
        int queued = 0;
        if (run == null) {
            if (!startRun(nightOf, now)) {
                log.info("밤 스위프 건너뜀 — nightOf={} 이미 다른 곳이 돌렸다(run PK). trigger={}", nightOf, trigger);
                return Result.skipped(nightOf);
            }
            queued = planAll(nightOf, now);
        } else if (run.isFinished()) {
            log.info("밤 스위프 건너뜀 — nightOf={} 이미 끝난 밤. trigger={}", nightOf, trigger);
            return Result.skipped(nightOf);
        } else {
            log.warn("밤 스위프 이어서 — nightOf={} 도중에 멈춘 run 이 있다(계획은 다시 안 한다). trigger={}", nightOf, trigger);
        }

        int[] counts = claimAndBake(now, maxBakes);
        finishRun(nightOf, now, queued, counts[0], counts[1]);
        log.info("밤 스위프 끝 — nightOf={} 등록={} 굽기={} 이월={} trigger={}", nightOf, queued, counts[0], counts[1], trigger);
        return new Result(nightOf, true, queued, counts[0], counts[1]);
    }

    /** dev — 이 펫만 지금 계획·굽기. run 기록은 남기지 않는다(진짜 밤이 아니다). */
    public Result sweepPet(ZzalPet pet, Instant petNow) {
        LocalDate nightOf = AwakeClock.dateOf(petNow);
        int queued = planner.plan(pet, nightOf);
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
    boolean startRun(LocalDate nightOf, Instant now) {
        try {
            tx.execute(status -> runRepository.saveAndFlush(ZzalNightRun.start(nightOf, now, server)));
            return true;
        } catch (DataIntegrityViolationException e) {
            return false;
        }
    }

    void finishRun(LocalDate nightOf, Instant now, int queued, int claimed, int carried) {
        tx.execute(status -> {
            runRepository.findById(nightOf).ifPresent(r -> r.finish(now, queued, claimed, carried));
            return null;
        });
    }

    /** ALIVE 펫 전부 — 정산(23:00 자동 취침 포함) 뒤 계획. 펫마다 트랜잭션 하나(한 펫이 실패해도 다른 펫은 간다). */
    int planAll(LocalDate nightOf, Instant now) {
        List<Long> ids = petRepository.findByPhase(PetPhase.ALIVE).stream().map(ZzalPet::getId).toList();
        int queued = 0;
        for (Long id : ids) {
            try {
                queued += tx.execute(status -> petRepository.findByIdForUpdate(id).map(pet -> {
                    pet.settle(pet.now(now));
                    return planner.plan(pet, nightOf);
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
                .sorted(priority(pets))
                .toList();
        int claimed = 0;
        for (ZzalMotion m : ordered) {
            if (claimed >= cap) {
                break;
            }
            if (claimOne(m.getId(), now)) {
                claimed++;
            }
        }
        return new int[]{claimed, Math.max(0, ordered.size() - claimed)};
    }

    /** 집기 — UPDATE … WHERE status='QUEUED' 가 1 을 돌려줄 때만 굽는다. 0 이면 다른 서버가 먼저 집었다. */
    boolean claimOne(Long motionId, Instant now) {
        int won = motionRepository.claim(motionId, now, server);
        if (won != 1) {
            return false;
        }
        nightExecutor.execute(() -> motionService.bakeNow(motionId));
        return true;
    }

    /** 선물 > 케어 미스 0인 날 수(3층 streak 의 자리) > 친밀도 > id. 펫이 없으면 맨 뒤. */
    static Comparator<ZzalMotion> priority(Map<Long, ZzalPet> pets) {
        Comparator<ZzalMotion> gift = Comparator.comparing((ZzalMotion m) -> m.getSeq() >= 101 ? 0 : 1);
        Comparator<ZzalMotion> streak = Comparator.comparing((ZzalMotion m) ->
                pets.containsKey(m.getPetId()) ? -pets.get(m.getPetId()).getZeroMissDays() : Integer.MAX_VALUE);
        Comparator<ZzalMotion> intimacy = Comparator.comparing((ZzalMotion m) ->
                pets.containsKey(m.getPetId()) ? -pets.get(m.getPetId()).getIntimacy() : Integer.MAX_VALUE);
        return gift.thenComparing(streak).thenComparing(intimacy).thenComparing(ZzalMotion::getId);
    }

    // ── 시각 ──────────────────────────────────────────────────────────────

    /** 이 순간이 속한 밤(23:00 이 속한 KST 날짜). 23:00 정각에 부르면 오늘. */
    static LocalDate nightOf(Instant now) {
        ZonedDateTime z = now.atZone(ZzalRules.ZONE);
        return z.toLocalTime().isBefore(ZzalRules.AUTO_WAKE_AT) ? z.toLocalDate().minusDays(1) : z.toLocalDate();
    }

    /** 23:00~10:00 창 안이면 그 밤, 아니면 null. */
    static LocalDate nightWindowOf(Instant now) {
        LocalTime t = now.atZone(ZzalRules.ZONE).toLocalTime();
        if (!t.isBefore(ZzalRules.AUTO_SLEEP_AT)) {
            return now.atZone(ZzalRules.ZONE).toLocalDate();
        }
        if (t.isBefore(ZzalRules.AUTO_WAKE_AT)) {
            return now.atZone(ZzalRules.ZONE).toLocalDate().minusDays(1);
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
