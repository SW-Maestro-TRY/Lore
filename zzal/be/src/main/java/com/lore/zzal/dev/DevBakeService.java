package com.lore.zzal.dev;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.zzal.generation.GenJob;
import com.lore.zzal.generation.GenJobRepository;
import com.lore.zzal.generation.GenKind;
import com.lore.zzal.generation.GenStepRecordRepository;
import com.lore.zzal.generation.HatchService;
import com.lore.zzal.generation.Layer2Service;
import com.lore.zzal.generation.PetHatchRequested;
import com.lore.zzal.motion.MotionCatalog;
import com.lore.zzal.motion.MotionStatus;
import com.lore.zzal.motion.ZzalMotion;
import com.lore.zzal.motion.ZzalMotionRepository;
import com.lore.zzal.night.BakeTrigger;
import com.lore.zzal.pet.Layer2Status;
import com.lore.zzal.pet.PetPhase;
import com.lore.zzal.pet.PetService;
import com.lore.zzal.pet.ZzalPet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 개발 리모컨 — 굽기(부화·2층·선물/심화) 지정 트리거와 진행 조회(remote-control-spec 4장).
 *
 * <h3>★★ 돈이 나가는 길 — 서버 스위치가 정한다</h3>
 * 그림 클라이언트는 서버 전체에 하나다({@code app.zzal.generation.real}). 그래서 요청의 {@code real} 은
 * <b>"나는 지금 진짜로 굽는 줄 안다"</b> 는 확인이고, 서버 스위치와 다르면 거절한다.
 * <ul>
 *   <li>{@code real=true} 인데 스위치가 꺼짐 → 409 "과금 스위치 꺼짐". 아무것도 안 굽는다</li>
 *   <li>{@code real=false} 인데 스위치가 켜짐 → 409. 가짜라고 믿고 눌렀다가 돈이 나가는 일을 막는다</li>
 * </ul>
 * 스위치를 켜는 것({@code ZZAL_GENERATION_REAL=true})은 과금 설정이라 사람이 직접 한다(결정 1). 하루 상한은 두지 않는다(결정 1).
 *
 * <h3>★ 굽기는 운영과 같은 길이다</h3>
 * 부화 = {@link PetHatchRequested}(커밋 뒤 {@code HatchService.hatch}), 2층 = {@link Layer2Service#schedule},
 * 선물·심화 = {@link BakeTrigger#devBake}(큐 → 집기 → {@code MotionService.bakeNow}). 리모컨만의 굽는 코드는 없다.
 */
@Service
@ConditionalOnProperty(name = "app.zzal.dev-tools", havingValue = "true")
public class DevBakeService {

    private static final Logger log = LoggerFactory.getLogger(DevBakeService.class);

    /** 예상 비용(실측). 부화 = 시트 0.063 + 문단 0.018 + 격자 0.086(GenJob 머리말), 2층·동작 = 격자 한 판 0.086. */
    static final BigDecimal HATCH_USD = new BigDecimal("0.19");
    static final BigDecimal GRID_USD = new BigDecimal("0.09");

    private final PetService petService;
    private final DevService devService;
    private final GenJobRepository jobRepository;
    private final GenStepRecordRepository stepRepository;
    private final HatchService hatchService;
    private final Layer2Service layer2Service;
    private final ZzalMotionRepository motionRepository;
    private final MotionCatalog catalog;
    private final BakeTrigger bakeTrigger;
    private final ApplicationEventPublisher events;
    private final boolean real;

    public DevBakeService(PetService petService, DevService devService, GenJobRepository jobRepository,
                          GenStepRecordRepository stepRepository, HatchService hatchService,
                          Layer2Service layer2Service, ZzalMotionRepository motionRepository, MotionCatalog catalog,
                          BakeTrigger bakeTrigger, ApplicationEventPublisher events,
                          @Value("${app.zzal.generation.real:false}") boolean real) {
        this.petService = petService;
        this.devService = devService;
        this.jobRepository = jobRepository;
        this.stepRepository = stepRepository;
        this.hatchService = hatchService;
        this.layer2Service = layer2Service;
        this.motionRepository = motionRepository;
        this.catalog = catalog;
        this.bakeTrigger = bakeTrigger;
        this.events = events;
        this.real = real;
    }

    /** 서버의 진짜 생성 스위치. */
    public boolean isReal() {
        return real;
    }

    /**
     * 굽기를 시작한다.
     *
     * @param kind   hatch · layer2 · gift
     * @param wantReal 진짜로 굽는 줄 아는가(서버 스위치와 같아야 한다)
     * @param seq    gift 일 때 동작 seq(없으면 첫 선물)
     */
    @Transactional
    public DevResponses.DevBake bake(Long userId, Long petId, String kind, boolean wantReal, Integer seq, Instant realNow) {
        String k = kind == null ? "" : kind.toLowerCase(Locale.ROOT);
        if (!Set.of("hatch", "layer2", "gift").contains(k)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "kind 는 hatch · layer2 · gift 중 하나예요: " + kind);
        }
        if (wantReal && !real) {
            throw new BusinessException(ErrorCode.ZZAL_FEATURE_LOCKED,
                    "과금 스위치 꺼짐 — 이 서버는 ZZAL_GENERATION_REAL=false 라 진짜로 굽지 않아요(켜는 것은 사람이 직접)");
        }
        if (!wantReal && real) {
            throw new BusinessException(ErrorCode.ZZAL_FEATURE_LOCKED,
                    "이 서버는 진짜 생성(ZZAL_GENERATION_REAL=true)이라 가짜 굽기를 할 수 없어요 — 굽으면 돈이 나갑니다. real:true 로 다시 보내세요");
        }
        BigDecimal cost = !real ? BigDecimal.ZERO : switch (k) {
            case "hatch" -> HATCH_USD;
            default -> GRID_USD;
        };
        String[] jobId = new String[1];
        DevResponses.DevResult result = devService.run(userId, petId, realNow, (pet, now) -> {
            jobId[0] = switch (k) {
                case "hatch" -> hatch(pet, now);
                case "layer2" -> layer2(pet, now);
                default -> gift(pet, seq, now);
            };
            return List.of("굽기 시작 — " + k + (real ? " (진짜, 예상 $" + cost + ")" : " (가짜, 돈 0)"));
        });
        log.info("dev 굽기 — petId={} kind={} real={} jobId={}", petId, k, real, jobId[0]);
        String note = switch (k) {
            case "hatch" -> "다시 부화 — 끝나면 부화 직후 상태(게이지·튜토리얼은 그대로)로 살아나고 2층이 뒤에서 구워집니다"
                    + (real ? "(2층 격자 약 $" + GRID_USD + " 별도)" : "");
            case "layer2" -> "2층 8종을 처음부터 다시 굽습니다. 그동안 2층은 '연습 중'";
            default -> "선물·심화 한 판 — 끝나면 검수 대기(REVIEW). 열려면 events/gift-open";
        };
        return new DevResponses.DevBake(jobId[0], k, real, cost, note, result);
    }

    /** 다시 부화 — 성공한 부화 단계를 지우고(안 지우면 실행기가 건너뛰어 아무것도 안 굽는다) HATCHING 으로 돌린다. */
    private String hatch(ZzalPet pet, Instant now) {
        if (pet.isHatching()) {
            throw new BusinessException(ErrorCode.ZZAL_PET_ALREADY_HATCHING, "이미 굽는 중이에요");
        }
        if (pet.getName() == null || pet.getName().isBlank()) {
            throw new BusinessException(ErrorCode.ZZAL_FEATURE_LOCKED, "이름이 없는 알은 살아날 수 없어요(초안) — 이름부터");
        }
        int dropped = 0;
        for (var r : stepRepository.findSucceededByPet(pet.getId(), GenKind.HATCH)) {
            stepRepository.delete(r);
            dropped++;
        }
        String version = pet.getHatchPipelineVersion() != null ? pet.getHatchPipelineVersion() : hatchService.currentVersion();
        pet.devRehatch(now);
        GenJob job = jobRepository.save(GenJob.start(pet.getId(), GenKind.HATCH, 1, version, Instant.now()));
        // ★ 운영 부화와 같은 길 — 커밋 뒤에 굽기가 시작된다(PetHatchListener, AFTER_COMMIT).
        events.publishEvent(new PetHatchRequested(job.getId(), pet.getId(), version));
        log.info("dev 다시 부화 — petId={} jobId={} 지운 단계 {}", pet.getId(), job.getId(), dropped);
        return "hatch-" + job.getId();
    }

    /** 2층 처음부터 — 관리자 재시도(AdminLayerService.retry)와 같은 순서. READY 여도 다시 굽는다(리모컨은 그걸 보려는 것). */
    private String layer2(ZzalPet pet, Instant now) {
        if (!pet.isAlive()) {
            throw new BusinessException(ErrorCode.ZZAL_PET_NOT_ALIVE);
        }
        if (pet.getLayer2Status() == Layer2Status.RUNNING) {
            throw new BusinessException(ErrorCode.ZZAL_PET_ALREADY_HATCHING, "2층이 지금 굽는 중이에요");
        }
        for (var r : stepRepository.findSucceededByPet(pet.getId(), GenKind.LAYER2)) {
            stepRepository.delete(r);
        }
        pet.resetLayer2(now);
        Long petId = pet.getId();
        afterCommit(() -> layer2Service.schedule(petId));
        return "layer2-" + Instant.now().getEpochSecond();
    }

    /** 선물·심화 한 판 — 그 줄을 비우고(NONE) 큐에 올려 집어서 굽는다. */
    private String gift(ZzalPet pet, Integer seq, Instant now) {
        if (!pet.isAlive()) {
            throw new BusinessException(ErrorCode.ZZAL_PET_NOT_ALIVE);
        }
        int s = seq != null ? seq : (catalog.gifts().isEmpty() ? 101 : catalog.gifts().getFirst().seq());
        var spec = catalog.bySeq(s)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_INPUT, "카탈로그에 없는 동작 번호예요: " + s));
        if (!catalog.isBakeable(spec.key())) {
            throw new BusinessException(ErrorCode.ZZAL_FEATURE_LOCKED,
                    "지시문이 없어 구울 수 없는 동작이에요: %s (app.zzal.gift-motions·advanced-motions)".formatted(spec.key()));
        }
        ZzalMotion row = motionRepository.findByPetIdAndSeq(pet.getId(), s)
                .orElseThrow(() -> new BusinessException(ErrorCode.ZZAL_FEATURE_LOCKED, "이 아이에게 동작 행 %d 가 없어요".formatted(s)));
        if (row.getStatus() == MotionStatus.BAKING) {
            throw new BusinessException(ErrorCode.ZZAL_PET_ALREADY_HATCHING, "그 동작은 지금 굽는 중이에요");
        }
        row.devSetStatus(MotionStatus.NONE, null, now);
        if (!bakeTrigger.devBake(row, now)) {
            throw new BusinessException(ErrorCode.ZZAL_FEATURE_LOCKED, "큐에 올리지 못했어요(지금 %s)".formatted(row.getStatus()));
        }
        return "gift-" + s + "-" + Instant.now().getEpochSecond();
    }

    /** 굽기 진행. 열쇠 모양이 틀리면 400. */
    @Transactional(readOnly = true)
    public DevResponses.DevBakeProgress progress(Long userId, Long petId, String jobId) {
        ZzalPet pet = petService.get(userId, petId);
        String[] parts = jobId == null ? new String[0] : jobId.split("-");
        try {
            switch (parts.length > 0 ? parts[0] : "") {
                case "hatch" -> {
                    long genId = Long.parseLong(parts[1]);
                    List<GenJob> jobs = jobRepository.findByPetIdOrderByIdAsc(petId).stream()
                            .filter(j -> j.getKind() == GenKind.HATCH && j.getId() >= genId)
                            .toList();
                    String version = pet.getHatchPipelineVersion() != null ? pet.getHatchPipelineVersion() : hatchService.currentVersion();
                    boolean finished = pet.getPhase() != PetPhase.HATCHING;
                    return new DevResponses.DevBakeProgress(jobId, "hatch", pet.getPhase().name(),
                            hatchService.stepsDone(petId, version), hatchService.stepsTotal(version), sum(jobs), finished);
                }
                case "layer2" -> {
                    Instant since = Instant.ofEpochSecond(Long.parseLong(parts[1]));
                    List<GenJob> jobs = jobRepository.findByPetIdOrderByIdAsc(petId).stream()
                            .filter(j -> j.getKind() == GenKind.LAYER2 && !j.getStartedAt().isBefore(since))
                            .toList();
                    Layer2Status st = pet.getLayer2Status();
                    return new DevResponses.DevBakeProgress(jobId, "layer2", st.name(), null, null, sum(jobs),
                            st == Layer2Status.READY || st == Layer2Status.FAILED);
                }
                case "gift" -> {
                    int seq = Integer.parseInt(parts[1]);
                    Instant since = Instant.ofEpochSecond(Long.parseLong(parts[2]));
                    ZzalMotion row = motionRepository.findByPetIdAndSeq(petId, seq)
                            .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_INPUT, "동작 행이 없어요: " + seq));
                    List<GenJob> jobs = jobRepository.findByPetIdOrderByIdAsc(petId).stream()
                            .filter(j -> j.getKind() == GenKind.MOTION && row.getId().equals(j.getMotionId())
                                    && !j.getStartedAt().isBefore(since))
                            .toList();
                    MotionStatus st = row.getStatus();
                    boolean finished = st != MotionStatus.QUEUED && st != MotionStatus.BAKING;
                    return new DevResponses.DevBakeProgress(jobId, "gift", st.name(), null, null, sum(jobs), finished);
                }
                default -> throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException | ArrayIndexOutOfBoundsException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "굽기 열쇠 모양이 아니에요: " + jobId);
        }
    }

    private static BigDecimal sum(List<GenJob> jobs) {
        return jobs.stream().map(GenJob::getTotalCostUsd).filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static void afterCommit(Runnable task) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            task.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                task.run();
            }
        });
    }
}
