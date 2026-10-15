package com.lore.zzal.dev;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.zzal.generation.GenJob;
import com.lore.zzal.generation.GenJobRepository;
import com.lore.zzal.generation.GenKind;
import com.lore.zzal.generation.GenStatus;
import com.lore.zzal.generation.GenStepRecord;
import com.lore.zzal.generation.GenStepRecordRepository;
import com.lore.zzal.generation.HatchService;
import com.lore.zzal.generation.steps.IdentityStep;
import com.lore.zzal.generation.steps.SheetStep;
import com.lore.zzal.pet.PetPhase;
import com.lore.zzal.pet.PetService;
import com.lore.zzal.pet.ZzalPet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 개발 리모컨 — <b>온보딩을 다시 밟기 위한</b> 두 손잡이(2026-10-13).
 *
 * <ol>
 *   <li>{@link #resetMe} — 내 계정의 펫과 딸린 행을 지워 <b>펫 없는 상태</b>로 되돌린다. 랜딩부터 다시 밟는다</li>
 *   <li>{@link #hatchStage} — 알 화면의 굽기 진행을 1/N…N/N·완료·실패로 강제한다. 가짜 생성은 몇 초 만에
 *       끝나 알 화면의 단계 표시·실패 화면을 눈으로 볼 틈이 없어서다</li>
 * </ol>
 *
 * <h3>★★ 지운 것은 되돌릴 수 없다 — 그래서 운영에는 이 빈이 없다</h3>
 * {@code app.zzal.dev-tools=true} 일 때만 뜬다. 지우는 범위는 <b>로그인한 내 펫</b>뿐이다(user_id 로 고른 펫 id 만).
 * 피드백·행동 기록(zzal_feedback·zzal_event)은 남긴다 — 사람이 남긴 말과 깔때기 기록이라 테스트 계정이어도 지우지 않는다.
 *
 * <h3>★ 굽기 단계 강제는 "N 단계까지 끝난 척" 이다</h3>
 * 단계 기록의 <b>상태만</b> 바꾼다(성공 → QUEUED 로 내려 두기, 다시 올리기). 산출물(시트 그림 키·정체성 문단)은
 * 그대로 두므로 「완료」 가 그 산출물로 살아난다. 완료는 운영과 같은 {@link HatchService#completeIfReady} 를 부른다
 * (1층 동작 깔기·2층 굽기 예약까지 같은 길). 그림을 새로 굽지 않으니 돈이 나가지 않는다.
 */
@Service
@ConditionalOnProperty(name = "app.zzal.dev-tools", havingValue = "true")
public class DevOnboardingService {

    private static final Logger log = LoggerFactory.getLogger(DevOnboardingService.class);

    /** 지우는 순서(자식 → 부모). 외래 키가 없어 순서가 강제되진 않지만, 생기더라도 그대로 돌게 둔다. */
    private static final List<String[]> RESET_ORDER = List.of(
            new String[]{"zzal_motion_candidate", "motion_id in (select id from zzal_motion where pet_id in (%s))"},
            new String[]{"zzal_gen_step", "job_id in (select id from zzal_gen_job where pet_id in (%s))"},
            new String[]{"zzal_gen_job", "pet_id in (%s)"},
            new String[]{"zzal_motion", "pet_id in (%s)"},
            new String[]{"zzal_motion_wish", "pet_id in (%s)"},
            new String[]{"zzal_postcard", "pet_id in (%s)"},
            new String[]{"zzal_scene", "pet_id in (%s)"},
            new String[]{"zzal_chat_turn", "pet_id in (%s)"},
            new String[]{"zzal_chat_call", "pet_id in (%s)"},
            new String[]{"zzal_chat_session", "pet_id in (%s)"},
            new String[]{"zzal_game", "pet_id in (%s)"},
            new String[]{"zzal_piece", "pet_id in (%s)"},
            new String[]{"zzal_shares", "pet_id in (%s)"},
            new String[]{"zzal_pet", "id in (%s)"});

    /** 가짜로 채운 단계에 남기는 모델 이름 — 기록을 보면 "dev 가 만든 성공" 인지 바로 보인다. */
    static final String FAKE_MODEL = "dev-remote";

    /** 이보다 오래 도는 굽기는 죽은 것으로 본다. */
    static final java.time.Duration STUCK_AFTER = java.time.Duration.ofMinutes(10);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final PetService petService;
    private final HatchService hatchService;
    private final GenJobRepository jobRepository;
    private final GenStepRecordRepository stepRepository;
    private final boolean generationReal;

    public DevOnboardingService(JdbcTemplate jdbc, TransactionTemplate tx, PetService petService,
                                HatchService hatchService, GenJobRepository jobRepository,
                                GenStepRecordRepository stepRepository,
                                @Value("${app.zzal.generation.real:false}") boolean generationReal) {
        this.generationReal = generationReal;
        this.jdbc = jdbc;
        this.tx = tx;
        this.petService = petService;
        this.hatchService = hatchService;
        this.jobRepository = jobRepository;
        this.stepRepository = stepRepository;
    }

    // ── 1. 내 펫 지우기 ────────────────────────────────────────────────────

    /** 내 펫 전부와 딸린 행을 한 트랜잭션으로 지운다. 응답 = 표별 지운 행 수(지운 순서대로). */
    public DevResponses.DevReset resetMe(Long userId) {
        return tx.execute(status -> {
            List<Long> ids = jdbc.queryForList("select id from zzal_pet where user_id = ? order by id", Long.class, userId);
            Map<String, Integer> deleted = new LinkedHashMap<>();
            if (!ids.isEmpty()) {
                String in = String.join(",", ids.stream().map(String::valueOf).toList());   // Long 만 — 주입 여지 없음
                for (String[] t : RESET_ORDER) {
                    deleted.put(t[0], jdbc.update("delete from " + t[0] + " where " + t[1].formatted(in)));
                }
            }
            log.info("dev 내 펫 지우기 — userId={} petIds={} 지운 행 {}", userId, ids, deleted);
            return new DevResponses.DevReset(ids, deleted);
        });
    }

    // ── 2. 굽기 단계 강제 ──────────────────────────────────────────────────

    /**
     * @param stage 1..전체 단계 수 · done · failed
     */
    public DevResponses.DevHatchStage hatchStage(Long userId, Long petId, String stage) {
        String s = stage == null ? "" : stage.trim().toLowerCase(Locale.ROOT);
        List<String> effects = new ArrayList<>();
        String[] version = new String[1];
        boolean complete = tx.execute(st -> {
            ZzalPet pet = petService.devLock(userId, petId);
            version[0] = pet.getHatchPipelineVersion() != null ? pet.getHatchPipelineVersion() : hatchService.currentVersion();
            List<String> names = hatchService.stepNamesInOrder(version[0]);
            refuseWhileBaking(petId);
            switch (s) {
                case "failed" -> {
                    if (pet.getPhase() == PetPhase.ALIVE) {
                        reopen(pet, effects);   // 가짜 생성이면 알로 되돌린 뒤 실패시킨다(진짜면 reopen 이 거절)
                    }
                    if (pet.getPhase() != PetPhase.DRAFT && pet.getPhase() != PetPhase.HATCHING) {
                        throw conflict("굽는 중(DRAFT·HATCHING)인 알만 실패시킬 수 있어요 — 지금 " + pet.getPhase());
                    }
                    pet.markHatchFailed();
                    effects.add("부화 실패로 — " + pet.getPhase());
                    return false;
                }
                case "done" -> {
                    if (pet.getName() == null || pet.getName().isBlank()) {
                        throw conflict("이름이 없는 알은 살아날 수 없어요 — 캐릭터 칸에서 이름부터(리모컨 「이름·칩 채우기」)");
                    }
                    reopen(pet, effects);
                    effects.addAll(forceSteps(pet, names, names.size(), version[0]));
                    return true;
                }
                default -> {
                    int n = parseStage(s, names.size());
                    reopen(pet, effects);
                    effects.addAll(forceSteps(pet, names, n, version[0]));
                    return false;
                }
            }
        });
        if (complete) {
            // ★ 운영 굽기와 같은 끝맺음 — 트랜잭션 밖에서 부른다(굽기 실행기도 밖에서 부른다. 2층 예약이 커밋 뒤에 돈다).
            boolean alive = hatchService.completeIfReady(petId, version[0]);
            effects.add(alive ? "부화 완료 — 살아났어요(1층 동작 깔기·2층 굽기 예약까지 운영과 같은 길)"
                    : "완료하지 못했어요 — 단계 기록을 확인하세요");
        }
        log.info("dev 굽기 단계 강제 — petId={} stage={} 효과 {}", petId, s, effects);
        return new DevResponses.DevHatchStage(s, effects, petService.hatchProgress(userId, petId, Instant.now()));
    }

    /**
     * 실패했거나 이름까지 받은 초안이면 굽는 중(HATCHING)으로.
     *
     * ★ 살아 있는 아이(ALIVE)도 <b>가짜 생성일 때만</b> 알로 되돌린다 — 가짜 굽기는 10초 남짓이라 이름을 내면 곧바로 살아나,
     *   되돌리지 않으면 단계 표시를 볼 틈이 없다(2026-10-13 로컬 실측: 부화 job 10초). 되살릴 때 2층이 다시 구워지는데
     *   진짜 생성이면 그게 돈이라 거절한다. 동작 행·게이지는 「완료」 가 운영 부화와 같은 길로 다시 세운다(동작 행은 있는 것은 두고 빈 것만).
     */
    private void reopen(ZzalPet pet, List<String> effects) {
        PetPhase phase = pet.getPhase();
        if (phase == PetPhase.ALIVE) {
            if (generationReal) {
                throw conflict("진짜 생성(과금) 서버예요 — 살아난 아이를 알로 되돌리면 「완료」 때 2층이 다시 구워져 돈이 나가요. 「내 펫 지우고 처음부터」 를 쓰세요");
            }
            pet.devRehatch(Instant.now());
            effects.add("단계: ALIVE → HATCHING(가짜 생성 — 알로 되돌림)");
            return;
        }
        if (phase == PetPhase.DRAFT || phase == PetPhase.HATCHING) {
            if (phase == PetPhase.DRAFT && pet.getName() != null && !pet.getName().isBlank()) {
                pet.devRehatch(Instant.now());
                effects.add("단계: DRAFT → HATCHING(이름이 있는 초안)");
            }
            return;
        }
        if (phase == PetPhase.FAILED && pet.getName() != null && !pet.getName().isBlank()) {
            pet.devRehatch(Instant.now());
            effects.add("단계: FAILED → HATCHING");
            return;
        }
        throw conflict("이 아이는 %s 예요 — 굽기 단계는 알(DRAFT·HATCHING·이름 있는 FAILED, 가짜 생성이면 ALIVE 도)에서만. 처음부터는 「내 펫 지우고 처음부터」"
                .formatted(phase));
    }

    /** 앞의 n 단계는 성공, n+1 번째는 굽는 중, 나머지는 아직(QUEUED)으로 맞춘다. */
    private List<String> forceSteps(ZzalPet pet, List<String> names, int n, String version) {
        Long petId = pet.getId();
        Instant now = Instant.now();
        List<GenJob> jobs = jobRepository.findByPetIdOrderByIdAsc(petId).stream()
                .filter(j -> j.getKind() == GenKind.HATCH && version.equals(j.getPipelineVersion()))
                .toList();
        GenJob latest;
        if (jobs.isEmpty()) {
            // 굽기 기록이 하나도 없다 — 가짜 단계를 붙일 job 을 하나 만들고 바로 끝난 것으로 둔다(돈 0).
            latest = GenJob.start(petId, GenKind.HATCH, 1, version, now);
            latest.succeed(BigDecimal.ZERO, now);
            latest = jobRepository.save(latest);
        } else {
            latest = jobs.getLast();
        }
        List<GenStepRecord> records = new ArrayList<>();
        for (GenJob j : jobs) {
            records.addAll(stepRepository.findByJobIdOrderBySeqAsc(j.getId()));
        }
        List<String> notes = new ArrayList<>();
        int created = 0;
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            List<GenStepRecord> mine = records.stream().filter(r -> name.equals(r.getName())).toList();
            if (i < n) {
                if (mine.stream().anyMatch(GenStepRecord::isSucceeded)) {
                    continue;
                }
                // 내려 두었던 기록(산출물 보존)이 있으면 그것을 올린다 — 없으면 가짜 성공을 하나 만든다.
                GenStepRecord back = mine.stream().filter(r -> r.getOutputKey() != null || r.getOutputText() != null)
                        .reduce((a, b) -> b).orElse(null);
                if (back == null) {
                    back = stepRepository.findByJobIdAndName(latest.getId(), name).orElse(null);
                }
                if (back != null) {
                    back.devSetStatus(GenStatus.SUCCEEDED, now);
                } else {
                    GenStepRecord fake = GenStepRecord.start(latest.getId(), i + 1, name, now);
                    fake.succeed(fakeKey(pet, name), fakeText(name), FAKE_MODEL, BigDecimal.ZERO, now);
                    stepRepository.save(fake);
                    created++;
                }
            } else {
                GenStatus to = i == n ? GenStatus.RUNNING : GenStatus.QUEUED;
                for (GenStepRecord r : mine) {
                    if (r.isSucceeded() || r.getStatus() == GenStatus.RUNNING || r.getStatus() == GenStatus.QUEUED) {
                        r.devSetStatus(to, now);
                    }
                }
            }
        }
        notes.add("굽기 진행 %d/%d 로 맞춤%s".formatted(n, names.size(), created > 0 ? " (가짜 성공 단계 " + created + "개 새로 만듦)" : ""));
        return notes;
    }

    /** 가짜 단계의 산출물 — 시트는 이미 있는 그림(없으면 올린 원본)을 쓴다. 다른 단계 키는 화면에 안 쓰인다. */
    private static String fakeKey(ZzalPet pet, String step) {
        if (SheetStep.NAME.equals(step)) {
            return pet.getSheetImageKey() != null ? pet.getSheetImageKey() : pet.getSourceImageKey();
        }
        return null;
    }

    private static String fakeText(String step) {
        return IdentityStep.NAME.equals(step) ? "(dev 리모컨이 채운 가짜 정체성 문단)" : null;
    }

    /** 굽기 실행기가 지금 이 아이를 굽고 있으면 손대지 않는다 — 두 쪽이 같은 기록을 고쳐 엉킨다. */
    private void refuseWhileBaking(Long petId) {
        jobRepository.findByPetIdOrderByIdAsc(petId).stream()
                .filter(j -> j.getKind() == GenKind.HATCH)
                .reduce((a, b) -> b)
                .filter(GenJob::isRunning)
                // 10분 넘게 도는 job 은 죽은 것으로 본다(서버가 굽는 중에 내려간 경우) — 안 그러면 영영 못 누른다.
                .filter(j -> j.getStartedAt().isAfter(Instant.now().minus(STUCK_AFTER)))
                .ifPresent(j -> {
                    throw conflict("지금 굽는 중이에요(job %d %s) — 가짜 생성이면 몇 초 뒤 다시 눌러 주세요".formatted(j.getId(), j.getStatus()));
                });
    }

    private static int parseStage(String s, int total) {
        try {
            int n = Integer.parseInt(s);
            if (n < 0 || n > total) {
                throw new BusinessException(ErrorCode.INVALID_INPUT, "stage 는 0~%d · done · failed: %s".formatted(total, s));
            }
            return n;
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "stage 는 0~%d · done · failed: %s".formatted(total, s));
        }
    }

    private static BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.ZZAL_FEATURE_LOCKED, message);
    }
}
