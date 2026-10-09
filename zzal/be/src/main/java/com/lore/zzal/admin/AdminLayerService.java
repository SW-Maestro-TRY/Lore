package com.lore.zzal.admin;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.common.s3.S3Service;
import com.lore.common.s3.S3Storage;
import com.lore.common.user.User;
import com.lore.common.user.UserRepository;
import com.lore.zzal.generation.GenJobRepository;
import com.lore.zzal.generation.GenKind;
import com.lore.zzal.generation.GenStepRecord;
import com.lore.zzal.generation.GenStepRecordRepository;
import com.lore.zzal.generation.HatchService;
import com.lore.zzal.generation.Layer2Service;
import com.lore.zzal.generation.LayerCandidateBaker;
import com.lore.zzal.generation.RejectedGridArchive;
import com.lore.zzal.generation.steps.IdentityStep;
import com.lore.zzal.generation.steps.Layer2PostStep;
import com.lore.zzal.generation.steps.PostProcessStep;
import com.lore.zzal.generation.steps.SheetStep;
import com.lore.zzal.motion.MotionImageKeys;
import com.lore.zzal.motion.MotionSeeder;
import com.lore.zzal.pet.Layer2Status;
import com.lore.zzal.pet.PetPhase;
import com.lore.zzal.pet.ZzalPet;
import com.lore.zzal.pet.ZzalPetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * 관리자 1층·2층 복구(#696) — 실패한 층을 <b>사람이 고른 격자로</b> 살린다.
 *
 * <h3>★ 흐름</h3>
 * <ol>
 *   <li>목록 — 2층 FAILED·오래된 PENDING/RUNNING(살아 있는 펫) + 1층 실패(부화 FAILED) 펫.</li>
 *   <li>후보 — 관리자가 presign 으로 올린 격자(최대 3장)를 서버가 <b>자동 굽기와 같은 후처리</b>(게이트+자르기,
 *       2층이면 지금 1층 앵커 이어받기)에 태워 임시 자리에 둔다. 결과는 후보별 게이트 판정·미리보기 키.</li>
 *   <li>고르기 — 고른 후보를 새 판으로 올린다(옛 판은 그대로 남는다 = 보존). 나머지 후보 격자는 {@code rejected/} 로 보존하고
 *       임시 파일은 지운다.</li>
 * </ol>
 *
 * <h3>★ 2층 고르기 = 새 판 = 1층 8종 옮겨 싣기 + 후보 8종 + 후보 앵커(1층+2층 합본)</h3>
 * <h3>★ 1층 고르기 = 새 판 = 후보 8종 + 후보 앵커(1층만) · 2층은 PENDING 으로 되돌려 다시 굽는다</h3>
 * 1층 앵커(K·Hw)가 바뀌므로 옛 2층 그림은 더 이상 맞지 않는다. 2층 격자(grid2)는 남겨 두므로 다시 자르기만 한다(돈 안 듦).
 * 부화에 실패한(FAILED) 알이면 그 자리에서 살린다(관리자 재굽기와 같은 자리 확인).
 * ★ 고르기는 부화 시각을 건드리지 않는다 — 복구한 시각은 {@code recovered_at} 에만 남는다(#702).
 *
 * ★ 잠금은 다른 관리자 API 와 같다(스위치·{@link AdminGuard}·화면 noindex).
 */
@Service
public class AdminLayerService {

    private static final Logger log = LoggerFactory.getLogger(AdminLayerService.class);

    /** 한 번에 올릴 수 있는 후보 수. */
    public static final int MAX_CANDIDATES = 3;

    /** 이보다 오래 PENDING·RUNNING 이면 목록에 올린다. */
    static final Duration STALE = Duration.ofMinutes(30);

    private final AdminGuard adminGuard;
    private final ZzalPetRepository petRepository;
    private final UserRepository userRepository;
    private final GenJobRepository jobRepository;
    private final GenStepRecordRepository stepRepository;
    private final S3Service s3Service;
    private final S3Storage storage;
    private final S3Client s3Client;
    private final String bucket;
    private final LayerCandidateBaker baker;
    private final Layer2Service layer2Service;
    private final HatchService hatchService;
    private final MotionSeeder motionSeeder;
    private final RejectedGridArchive archive;
    private final TransactionTemplate tx;

    public AdminLayerService(AdminGuard adminGuard, ZzalPetRepository petRepository, UserRepository userRepository,
                             GenJobRepository jobRepository, GenStepRecordRepository stepRepository,
                             S3Service s3Service, S3Storage storage, S3Client s3Client,
                             @Value("${app.s3.content-bucket}") String bucket,
                             LayerCandidateBaker baker, Layer2Service layer2Service, HatchService hatchService,
                             MotionSeeder motionSeeder, RejectedGridArchive archive,
                             PlatformTransactionManager transactionManager) {
        this.adminGuard = adminGuard;
        this.petRepository = petRepository;
        this.userRepository = userRepository;
        this.jobRepository = jobRepository;
        this.stepRepository = stepRepository;
        this.s3Service = s3Service;
        this.storage = storage;
        this.s3Client = s3Client;
        this.bucket = bucket;
        this.baker = baker;
        this.layer2Service = layer2Service;
        this.hatchService = hatchService;
        this.motionSeeder = motionSeeder;
        this.archive = archive;
        this.tx = new TransactionTemplate(transactionManager);
    }

    // ── 응답 ─────────────────────────────────────────────────────────────

    /** 후보 한 장. {@code previewKeys} 는 key → webp S3 키(통과했을 때만). 화면은 {@code /{키}} 로 부른다. */
    public record Candidate(String candidateId, String gridKey, String gate, String message,
                            String previewKey, Map<String, String> previewKeys) {
    }

    /**
     * 목록 한 줄. {@code layer} = 1(부화 실패) 또는 2(2층 실패·대기·결함 표시).
     *
     * <ul>
     *   <li>{@code recovery} — 복구가 어디까지 왔나: {@code LOCAL_REQUESTED}(다시 만들기 요청 — 맥미니 러너가 집는다) ·
     *       {@code CANDIDATES}(올라온 후보가 있다 — 사람이 고른다) · {@code WAITING}(아무것도 없음)</li>
     *   <li>{@code currentKeys} — 지금 사용자에게 보이는 그 층 8종(key → webp 키). 2층은 READY 일 때만(아니면 그 판에 2층이 없다)</li>
     *   <li>{@code recoveredAt} — 마지막으로 손으로 고친 시각(부화 시각과 따로)</li>
     * </ul>
     */
    public record Item(Long petId, String name, int layer, String phase, String layer2Status, boolean flagged,
                       int attempts, String lastError, Instant updatedAt, int basicRound,
                       String sheetKey, String identityText, String anchorsKey,
                       List<String> rejectedKeys, List<Candidate> candidates,
                       String recovery, Instant regenRequestedAt, Instant recoveredAt,
                       Map<String, String> currentKeys) {
    }

    /** 다시 만들기 요청 결과. */
    public record Regen(Long petId, int layer, Instant regenRequestedAt) {
    }

    /** 러너가 집을 상태 값 — 선물 재생성(LOCAL_REQUESTED)과 같은 이름. */
    public static final String LOCAL_REQUESTED = "LOCAL_REQUESTED";

    /** 고르기 결과. */
    public record Picked(Long petId, int layer, String candidateId, int basicRound, String phase,
                         String layer2Status) {
    }

    /** 수동 등록·재시도 결과. */
    public record State(Long petId, String layer2Status, int attempts, boolean flagged) {
    }

    // ── 목록 ─────────────────────────────────────────────────────────────

    public List<Item> list(Long adminUserId, Instant now) {
        adminGuard.require(adminUserId);
        return tx.execute(s -> {
            List<Item> out = new ArrayList<>();
            java.util.Set<Long> seen = new java.util.HashSet<>();
            // ★ 결함 표시(flagged)는 노출 상태와 따로 간다(#702) — READY 인 채로 목록에 오른다.
            for (ZzalPet p : petRepository.findByPhaseAndLayer2FlaggedTrueOrderByIdDesc(PetPhase.ALIVE)) {
                if (seen.add(p.getId())) {
                    out.add(item(p, 2));
                }
            }
            for (ZzalPet p : petRepository.findByPhaseAndLayer2StatusInOrderByIdDesc(PetPhase.ALIVE,
                    List.of(Layer2Status.FAILED, Layer2Status.PENDING, Layer2Status.RUNNING))) {
                boolean stale = p.getLayer2Status() != Layer2Status.FAILED
                        && (p.getLayer2UpdatedAt() == null || p.getLayer2UpdatedAt().isBefore(now.minus(STALE)));
                if ((p.getLayer2Status() == Layer2Status.FAILED || stale) && seen.add(p.getId())) {
                    out.add(item(p, 2));
                }
            }
            for (ZzalPet p : petRepository.findByPhase(PetPhase.FAILED)) {
                out.add(item(p, 1));
            }
            out.sort(Comparator.comparing(Item::petId).reversed());
            return out;
        });
    }

    private Item item(ZzalPet p, int layer) {
        String sheet = p.getSheetImageKey();
        String identity = p.getIdentityText();
        if (sheet == null || identity == null) {
            // 부화에 실패한 알은 펫 행에 시트·문단이 없다 — 성공한 단계 기록에서 꺼낸다.
            for (GenStepRecord r : stepRepository.findSucceededByPet(p.getId(), GenKind.HATCH)) {
                if (sheet == null && SheetStep.NAME.equals(r.getName())) {
                    sheet = r.getOutputKey();
                }
                if (identity == null && IdentityStep.NAME.equals(r.getName())) {
                    identity = r.getOutputText();
                }
            }
        }
        String lastError = layer == 2 ? p.getLayer2LastError() : lastHatchError(p.getId());
        List<Candidate> cands = CandidateList.parse(layer == 1 ? p.getLayer1Candidates() : p.getLayer2Candidates())
                .stream().map(c -> c.toResponse(p.getId(), layer, baker)).toList();
        Instant regen = p.getRegenRequestedAt(layer);
        String recovery = regen != null ? LOCAL_REQUESTED : cands.isEmpty() ? "WAITING" : "CANDIDATES";
        return new Item(p.getId(), p.getName(), layer, p.getPhase().name(), p.getLayer2Status().name(),
                p.isLayer2Flagged(), layer == 2 ? p.getLayer2Attempts() : hatchAttempts(p.getId()), lastError,
                layer == 2 ? p.getLayer2UpdatedAt() : p.getHatchStartedAt(), p.getBasicRound(), sheet, identity,
                p.getBasicRound() > 0 ? MotionImageKeys.anchors(p.getId(), p.getBasicRound()) : null,
                rejectedKeys(p.getId(), layer), cands, recovery, regen, p.getRecoveredAt(), currentKeys(p, layer));
    }

    /** 지금 사용자에게 보이는 그 층 8종. 판이 없거나(부화 실패) 2층이 READY 가 아니면 빈 맵. */
    private Map<String, String> currentKeys(ZzalPet p, int layer) {
        if (p.getBasicRound() <= 0 || !p.isAlive() || (layer == 2 && !p.isLayer2Ready())) {
            return Map.of();
        }
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (String k : baker.keys(layer)) {
            out.put(k, MotionImageKeys.basic(p.getId(), p.getBasicRound(), k));
        }
        return out;
    }

    private String lastHatchError(Long petId) {
        return jobRepository.findByPetIdOrderByIdAsc(petId).stream()
                .filter(j -> j.getKind() == GenKind.HATCH && j.getErrorCode() != null)
                .reduce((a, b) -> b)
                .map(j -> "부화 실패 — 마지막 시도 %d · %s".formatted(j.getAttempt(), j.getErrorCode()))
                .orElse("부화 실패");
    }

    private int hatchAttempts(Long petId) {
        return (int) jobRepository.findByPetIdOrderByIdAsc(petId).stream()
                .filter(j -> j.getKind() == GenKind.HATCH).count();
    }

    /**
     * 보존된 격자 키 — {@code rejected/} 아래 그 층의 것({@code -grid.png}·{@code -grid2.png}).
     * ★ 보존 키는 DB 에 안 남는다(RejectedGridArchive 주석) — S3 를 직접 훑는다. 실패해도 목록은 나간다.
     */
    private List<String> rejectedKeys(Long petId, int layer) {
        String suffix = layer == 1 ? "-grid.png" : "-grid2.png";
        try {
            return s3Client.listObjectsV2(ListObjectsV2Request.builder()
                            .bucket(bucket).prefix("images/zzal/pets/%d/rejected/".formatted(petId)).build())
                    .contents().stream().map(S3Object::key).filter(k -> k.endsWith(suffix)).sorted().toList();
        } catch (RuntimeException e) {
            log.warn("보존 격자 목록을 못 읽었다 — petId={} : {}", petId, String.valueOf(e));
            return List.of();
        }
    }

    // ── 2층 수동 등록·재시도 ─────────────────────────────────────────────

    /**
     * 통과했지만 결함인 2층을 목록에 올린다 — <b>표시만</b>(#702). 2층 상태·올라간 그림·사용자 화면은 그대로다.
     * 사용자 화면이 바뀌는 것은 후보를 고를 때({@link #pick}) 하나뿐이다.
     */
    public State flag(Long adminUserId, Long petId, String reason, Instant now) {
        adminGuard.require(adminUserId);
        return tx.execute(s -> {
            ZzalPet p = alive(petId);
            if (p.getLayer2Status() == Layer2Status.RUNNING || p.getLayer2Status() == Layer2Status.PENDING) {
                throw new BusinessException(ErrorCode.ZZAL_PET_ALREADY_HATCHING,
                        "2층이 아직 굽는 중입니다(지금 %s)".formatted(p.getLayer2Status()));
            }
            p.flagLayer2(reason == null || reason.isBlank() ? "관리자 수동 등록" : "관리자 수동 등록 — " + reason);
            log.info("2층 결함 표시 — petId={} 상태={} (admin={})", petId, p.getLayer2Status(), adminUserId);
            return state(p);
        });
    }

    /** 결함 표시를 거둔다 — 목록에서 내린다. 노출 상태는 그대로. */
    public State unflag(Long adminUserId, Long petId) {
        adminGuard.require(adminUserId);
        return tx.execute(s -> {
            ZzalPet p = alive(petId);
            p.unflagLayer2();
            log.info("2층 결함 표시 해제 — petId={} (admin={})", petId, adminUserId);
            return state(p);
        });
    }

    private static State state(ZzalPet p) {
        return new State(p.getId(), p.getLayer2Status().name(), p.getLayer2Attempts(), p.isLayer2Flagged());
    }

    /**
     * 운영 API 로 2층을 처음부터 다시 굽는다(시도 수 0부터). <b>돈이 든다</b> — 기본은 후보 업로드 길.
     * 2층 격자·자르기 기록을 보존 후 버리고 PENDING 으로 돌린 뒤, 커밋 뒤에 넘긴다.
     */
    public State retry(Long adminUserId, Long petId, Instant now) {
        adminGuard.require(adminUserId);
        State st = tx.execute(s -> {
            ZzalPet p = alive(petId);
            if (p.getLayer2Status() == Layer2Status.RUNNING) {
                throw new BusinessException(ErrorCode.ZZAL_PET_ALREADY_HATCHING, "2층이 지금 굽는 중입니다");
            }
            // ★ READY(결함 표시만 된 것)는 막는다(#702) — PENDING 으로 돌리는 순간 사용자 2층이 "연습 중" 으로 잠기고,
            //   운영 굽기가 또 실패하면 FAILED 로 남는다. 결함 표시된 펫은 후보(맥미니 다시 만들기·직접 올리기)로 고친다.
            if (p.getLayer2Status() == Layer2Status.READY) {
                throw new BusinessException(ErrorCode.INVALID_INPUT,
                        "2층이 READY 입니다 — 사용자 화면을 잠그지 않도록 후보를 올려 고르세요(다시 만들기·후보 올리기)");
            }
            discardLayer2Steps(petId, true);
            p.resetLayer2(now);
            return state(p);
        });
        layer2Service.schedule(petId);
        log.info("2층 재시도 — petId={} (admin={})", petId, adminUserId);
        return st;
    }

    // ── 다시 만들기(맥미니) ──────────────────────────────────────────────

    /**
     * 그 층을 맥미니에서 다시 만들어 달라고 표시한다(#702) — 러너가 10분마다 목록에서 {@code LOCAL_REQUESTED} 를 집어
     * Codex 로 후보 격자를 만들고 후보 올리기로 등록한다. <b>고르는 것은 사람</b>이다. 사용자 화면은 그대로.
     * 2층이면 목록에 남도록 결함 표시도 함께 건다(READY 펫이 목록에서 사라지지 않게).
     */
    public Regen requestRegen(Long adminUserId, Long petId, int layer, Instant now) {
        adminGuard.require(adminUserId);
        return tx.execute(s -> {
            ZzalPet p = petRepository.findByIdForUpdate(petId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.ZZAL_PET_NOT_FOUND));
            requireLayerTarget(p, layer);
            if (layer == 1 && p.getPhase() != PetPhase.FAILED) {
                throw new BusinessException(ErrorCode.INVALID_INPUT, "1층 다시 만들기는 부화에 실패한 알만 받습니다");
            }
            if (layer == 2 && p.getLayer2Status() == Layer2Status.READY && !p.isLayer2Flagged()) {
                p.flagLayer2("관리자 다시 만들기 요청");
            }
            p.requestRegen(layer, now);
            log.info("{}층 다시 만들기 요청 — petId={} (admin={})", layer, petId, adminUserId);
            return new Regen(petId, layer, p.getRegenRequestedAt(layer));
        });
    }

    /** 다시 만들기 요청을 거둔다. */
    public Regen cancelRegen(Long adminUserId, Long petId, int layer) {
        adminGuard.require(adminUserId);
        if (layer != 1 && layer != 2) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "층은 1 또는 2 입니다");
        }
        return tx.execute(s -> {
            ZzalPet p = petRepository.findByIdForUpdate(petId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.ZZAL_PET_NOT_FOUND));
            p.clearRegen(layer);
            log.info("{}층 다시 만들기 요청 취소 — petId={} (admin={})", layer, petId, adminUserId);
            return new Regen(petId, layer, null);
        });
    }

    // ── 업로드 주소 ──────────────────────────────────────────────────────

    /** 후보 격자를 올릴 presign(#702). 관리자 줄에 있어 봇 토큰으로도 부른다. png 만. */
    public S3Service.PresignedUpload presign(Long adminUserId, String contentType) {
        adminGuard.require(adminUserId);
        if (!"image/png".equals(contentType)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "후보 격자는 image/png 만 받습니다");
        }
        return s3Service.createUploadUrl(adminUserId, "zzal", contentType);
    }

    // ── 후보 ─────────────────────────────────────────────────────────────

    /**
     * 후보 격자(최대 3장)를 게이트+후처리에 태운다. 후보끼리는 나란히 돈다(각자 작업 폴더).
     *
     * @param layer     1 또는 2
     * @param gridKeys  presign 으로 올린 격자 키(관리자 본인 것·안 쓴 것)
     */
    public List<Candidate> candidates(Long adminUserId, Long petId, int layer, List<String> gridKeys, Instant now) {
        adminGuard.require(adminUserId);
        if (gridKeys == null || gridKeys.isEmpty() || gridKeys.size() > MAX_CANDIDATES) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "후보는 1~%d장입니다".formatted(MAX_CANDIDATES));
        }
        record Ctx(String version, int baseRound) {
        }
        Ctx ctx = tx.execute(s -> {
            ZzalPet p = petRepository.findById(petId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.ZZAL_PET_NOT_FOUND));
            requireLayerTarget(p, layer);
            for (String key : gridKeys) {
                s3Service.consume(adminUserId, key, now);
            }
            String v = p.getHatchPipelineVersion() != null ? p.getHatchPipelineVersion() : hatchService.currentVersion();
            return new Ctx(v, p.getBasicRound());
        });

        String batch = Long.toString(now.getEpochSecond(), 36);
        List<CandidateList.Entry> entries = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(gridKeys.size());
        try {
            List<CompletableFuture<CandidateList.Entry>> futures = new ArrayList<>();
            for (int i = 0; i < gridKeys.size(); i++) {
                String cid = batch + "-" + (i + 1);
                String key = gridKeys.get(i);
                futures.add(CompletableFuture.supplyAsync(() -> {
                    LayerCandidateBaker.Result r = baker.bake(petId, layer, cid, key, ctx.version(), ctx.baseRound());
                    return new CandidateList.Entry(cid, key, r.gate().name(), r.message(), ctx.baseRound());
                }, pool));
            }
            futures.forEach(f -> entries.add(f.join()));
        } finally {
            pool.shutdown();
        }

        tx.executeWithoutResult(s -> {
            ZzalPet p = petRepository.findByIdForUpdate(petId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.ZZAL_PET_NOT_FOUND));
            // ★ 앞서 올린 후보도 남겨 둔다(고를 때 함께 정리된다). 같은 화면에서 비교하려는 경우가 많다.
            List<CandidateList.Entry> all = new ArrayList<>(CandidateList.parse(layer == 1
                    ? p.getLayer1Candidates() : p.getLayer2Candidates()));
            all.addAll(entries);
            String joined = CandidateList.join(all);
            if (layer == 1) {
                p.setLayer1Candidates(joined);
            } else {
                p.setLayer2Candidates(joined);
            }
            // 후보가 왔다 — 다시 만들기 요청은 채워졌다(러너가 같은 요청을 또 집지 않는다)
            p.clearRegen(layer);
        });
        log.info("후보 {}장 처리 — petId={} layer={} 결과={} (admin={})", entries.size(), petId, layer,
                entries.stream().map(CandidateList.Entry::gate).toList(), adminUserId);
        return entries.stream().map(e -> e.toResponse(petId, layer, baker)).toList();
    }

    /** 그 층을 고칠 수 있는 펫인가. 2층 = 살아 있고 굽는 중이 아님. 1층 = 살아 있거나 부화 실패. */
    private static void requireLayerTarget(ZzalPet p, int layer) {
        if (layer == 2) {
            if (!p.isAlive()) {
                throw new BusinessException(ErrorCode.ZZAL_PET_ALREADY_HATCHING,
                        "2층은 살아 있는 펫만 고칠 수 있습니다(지금 %s)".formatted(p.getPhase()));
            }
            if (p.getLayer2Status() == Layer2Status.RUNNING) {
                throw new BusinessException(ErrorCode.ZZAL_PET_ALREADY_HATCHING, "2층이 지금 굽는 중입니다");
            }
            return;
        }
        if (layer == 1) {
            if (p.getPhase() != PetPhase.ALIVE && p.getPhase() != PetPhase.FAILED) {
                throw new BusinessException(ErrorCode.ZZAL_PET_ALREADY_HATCHING,
                        "1층은 살아 있거나 부화에 실패한 펫만 고칠 수 있습니다(지금 %s)".formatted(p.getPhase()));
            }
            if (p.getLayer2Status() == Layer2Status.RUNNING) {
                throw new BusinessException(ErrorCode.ZZAL_PET_ALREADY_HATCHING, "2층이 지금 굽는 중입니다");
            }
            return;
        }
        throw new BusinessException(ErrorCode.INVALID_INPUT, "층은 1 또는 2 입니다");
    }

    // ── 고르기 ───────────────────────────────────────────────────────────

    public Picked pick(Long adminUserId, Long petId, int layer, String candidateId, Instant now) {
        adminGuard.require(adminUserId);
        boolean[] scheduleLayer2 = {false};
        Picked picked = tx.execute(s -> {
            ZzalPet p = petRepository.findByIdForUpdate(petId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.ZZAL_PET_NOT_FOUND));
            requireLayerTarget(p, layer);
            List<CandidateList.Entry> all = CandidateList.parse(layer == 1 ? p.getLayer1Candidates() : p.getLayer2Candidates());
            CandidateList.Entry chosen = all.stream().filter(e -> e.id().equals(candidateId)).findFirst()
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "그 후보가 없습니다: " + candidateId));
            if (!LayerCandidateBaker.Gate.PASS.name().equals(chosen.gate())) {
                throw new BusinessException(ErrorCode.INVALID_INPUT,
                        "게이트를 통과한 후보만 고를 수 있습니다(%s)".formatted(chosen.gate()));
            }
            int from = p.getBasicRound();
            // ★ 후보를 자른 뒤 판이 바뀌었으면(그 사이 다른 층을 골랐다) 그 후보의 앵커는 옛 판 기준이다 — 다시 올려야 한다.
            if (layer == 2 && chosen.baseRound() != from) {
                throw new BusinessException(ErrorCode.ZZAL_PET_ALREADY_HATCHING,
                        "후보를 자른 뒤 판이 바뀌었습니다(%d → %d) — 후보를 다시 올리세요".formatted(chosen.baseRound(), from));
            }
            int round = from + 1;
            String src = LayerCandidateBaker.prefix(petId, layer, candidateId);
            String dst = MotionImageKeys.basicPrefix(petId, round);

            if (layer == 2) {
                if (from <= 0) {
                    throw new BusinessException(ErrorCode.INVALID_INPUT, "1층 판이 없습니다");
                }
                copyAll(MotionImageKeys.basicPrefix(petId, from), dst, baker.keys(1), ".webp");
                copyAll(src, dst, baker.keys(2), ".webp");
                copy(src + "/anchors.json", dst + "/anchors.json", "application/json");
                p.markBasicBaked(round);
                p.markLayer2Ready(now);
                discardLayer2Steps(petId, false);
            } else {
                if (p.getPhase() == PetPhase.FAILED) {
                    revive(p, now);
                }
                copyAll(src, dst, baker.keys(1), ".webp");
                copy(src + "/anchors.json", dst + "/anchors.json", "application/json");
                p.markBasicBaked(round);
                // ★ 1층 앵커(K·Hw)가 바뀌었다 — 옛 2층은 맞지 않는다. 격자(grid2)는 남기고 자르기만 다시(돈 안 듦).
                discardLayer2Post(petId);
                p.resetLayer2(now);
                scheduleLayer2[0] = true;
            }
            p.markRecovered(now);
            p.clearRegen(layer);
            // 나머지 후보 격자는 보존, 임시 파일은 정리
            cleanup(petId, layer, all, candidateId);
            if (layer == 1) {
                p.setLayer1Candidates(null);
            } else {
                p.setLayer2Candidates(null);
            }
            log.info("{}층 후보 고름 — petId={} 후보={} 판 {}→{} (admin={})", layer, petId, candidateId, from, round, adminUserId);
            return new Picked(petId, layer, candidateId, round, p.getPhase().name(), p.getLayer2Status().name());
        });
        if (scheduleLayer2[0]) {
            layer2Service.schedule(petId);
        }
        return picked;
    }

    /**
     * 부화에 실패한 알을 1층 후보로 살린다 — 관리자 재굽기와 같은 자리 확인 + 부화 완료와 같은 마무리.
     * 시트·문단은 성공했던 단계 기록에서 꺼낸다(없으면 살릴 수 없다).
     */
    private void revive(ZzalPet p, Instant now) {
        User owner = userRepository.findById(p.getUserId())
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        long occupied = petRepository.countByUserIdAndPhaseIn(owner.getId(), PetPhase.OCCUPYING_SLOT);
        if (occupied >= owner.getPetSlots()) {
            throw new BusinessException(ErrorCode.ZZAL_PET_LIMIT_REACHED,
                    "주인에게 이미 자리를 쓰는 아이가 %d마리 있습니다(자리 %d)".formatted(occupied, owner.getPetSlots()));
        }
        if (p.getName() == null || p.getName().isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "이름이 없는 알은 살릴 수 없습니다(이름 짓기 전 실패)");
        }
        String sheet = null;
        String identity = null;
        for (GenStepRecord r : stepRepository.findSucceededByPet(p.getId(), GenKind.HATCH)) {
            if (SheetStep.NAME.equals(r.getName())) {
                sheet = r.getOutputKey();
            }
            if (IdentityStep.NAME.equals(r.getName())) {
                identity = r.getOutputText();
            }
        }
        if (sheet == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "시트가 없어 살릴 수 없습니다 — 재굽기(rehatch)를 쓰세요");
        }
        if (p.getHatchPipelineVersion() == null) {
            p.setHatchPipelineVersion(hatchService.currentVersion());
        }
        // ★ 부화 시각(hatch_started_at·hatched_at)을 지금으로 덮지 않는다(#702) — recovered_at 에 남긴다.
        p.reviveByAdmin(sheet, identity, now);
        motionSeeder.seed(p.getId(), now);
        log.info("1층 후보로 부화 실패 알을 살림 — petId={}", p.getId());
    }

    private ZzalPet alive(Long petId) {
        ZzalPet p = petRepository.findByIdForUpdate(petId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ZZAL_PET_NOT_FOUND));
        if (!p.isAlive()) {
            throw new BusinessException(ErrorCode.ZZAL_PET_ALREADY_HATCHING,
                    "살아 있는 펫만 2층을 고칠 수 있습니다(지금 %s)".formatted(p.getPhase()));
        }
        return p;
    }

    /** 2층 단계 기록을 버린다. {@code withGrid} 면 격자도(보존 후). */
    private void discardLayer2Steps(Long petId, boolean withGrid) {
        for (GenStepRecord r : stepRepository.findSucceededByPet(petId, GenKind.LAYER2)) {
            if (PostProcessStep.GRID2.equals(r.getName())) {
                if (!withGrid) {
                    continue;
                }
                archive.preserve(petId, r);
            }
            stepRepository.delete(r);
        }
    }

    private void discardLayer2Post(Long petId) {
        for (GenStepRecord r : stepRepository.findSucceededByPet(petId, GenKind.LAYER2)) {
            if (Layer2PostStep.NAME.equals(r.getName())) {
                stepRepository.delete(r);
            }
        }
    }

    private void cleanup(Long petId, int layer, List<CandidateList.Entry> all, String chosen) {
        List<String> trash = new ArrayList<>();
        for (CandidateList.Entry e : all) {
            String prefix = LayerCandidateBaker.prefix(petId, layer, e.id());
            if (!e.id().equals(chosen)) {
                copyQuietly(e.gridKey(), "images/zzal/pets/%d/rejected/cand-%s-%s.png"
                        .formatted(petId, e.id(), layer == 1 ? "grid" : "grid2"), "image/png");
            }
            baker.keys(layer).forEach(k -> trash.add("%s/%s.webp".formatted(prefix, k)));
            trash.add(prefix + "/anchors.json");
        }
        try {
            storage.delete(trash);
        } catch (RuntimeException ex) {
            log.warn("후보 임시 파일 정리 실패(무시) — petId={} : {}", petId, String.valueOf(ex));
        }
    }

    private void copyAll(String fromPrefix, String toPrefix, List<String> keys, String ext) {
        for (String k : keys) {
            copy("%s/%s%s".formatted(fromPrefix, k, ext), "%s/%s%s".formatted(toPrefix, k, ext), "image/webp");
        }
    }

    /** 내려받아 다시 올린다(S3Storage 에 서버 측 copy 가 없다). 바이트는 그대로다. */
    private void copy(String from, String to, String contentType) {
        Path tmp = null;
        try {
            tmp = Files.createTempFile("zzal-layer-", ".bin");
            storage.download(from, tmp);
            storage.upload(to, tmp, contentType);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("복사 실패 %s → %s".formatted(from, to), e);
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (java.io.IOException ignored) {
                    // 임시 파일 정리 실패는 무시
                }
            }
        }
    }

    private void copyQuietly(String from, String to, String contentType) {
        try {
            copy(from, to, contentType);
        } catch (RuntimeException e) {
            log.warn("후보 격자 보존 실패(무시) — {} → {} : {}", from, to, String.valueOf(e));
        }
    }

    /**
     * 펫 행에 적어 두는 후보 목록 — {@code 후보id|격자키|게이트} 를 쉼표로 잇는다(새 표를 만들지 않으려고).
     * 게이트 사유 문장은 응답에만 나가고 여기엔 안 남는다(칸 길이).
     */
    static final class CandidateList {

        /** {@code baseRound} = 이 후보를 자를 때의 정식 판(2층은 그 판의 앵커를 이어받았다). */
        record Entry(String id, String gridKey, String gate, String message, int baseRound) {
            Candidate toResponse(Long petId, int layer, LayerCandidateBaker baker) {
                boolean pass = LayerCandidateBaker.Gate.PASS.name().equals(gate);
                String prefix = LayerCandidateBaker.prefix(petId, layer, id);
                Map<String, String> preview = pass
                        ? baker.keys(layer).stream().collect(Collectors.toMap(k -> k,
                        k -> "%s/%s.webp".formatted(prefix, k), (a, b) -> a, java.util.LinkedHashMap::new))
                        : Map.of();
                String first = pass ? preview.values().iterator().next() : null;
                return new Candidate(id, gridKey, gate, message, first, preview);
            }
        }

        static List<Entry> parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return List.of();
            }
            List<Entry> out = new ArrayList<>();
            for (String part : raw.split(",")) {
                String[] f = part.split("\\|", -1);
                if (f.length >= 3) {
                    int base = f.length >= 4 && !f[3].isBlank() ? Integer.parseInt(f[3]) : 0;
                    out.add(new Entry(f[0], f[1], f[2], null, base));
                }
            }
            return out;
        }

        static String join(List<Entry> entries) {
            String s = entries.stream().map(e -> e.id() + "|" + e.gridKey() + "|" + e.gate() + "|" + e.baseRound())
                    .collect(Collectors.joining(","));
            if (s.length() > 1900) {
                throw new BusinessException(ErrorCode.INVALID_INPUT, "후보가 너무 많이 쌓였습니다 — 먼저 하나를 고르세요");
            }
            return s;
        }
    }

}
