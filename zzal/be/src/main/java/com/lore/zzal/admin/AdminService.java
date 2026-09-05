package com.lore.zzal.admin;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.common.s3.S3Service;
import com.lore.zzal.admin.dto.AdminResponses;
import com.lore.zzal.generation.GenJobRepository;
import com.lore.zzal.motion.HumanVerdict;
import com.lore.zzal.motion.MotionCatalog;
import com.lore.zzal.motion.MotionSpec;
import com.lore.zzal.motion.MotionStatus;
import com.lore.zzal.motion.ZzalMotion;
import com.lore.zzal.motion.ZzalMotionRepository;
import com.lore.zzal.pet.ZzalPet;
import com.lore.zzal.pet.ZzalPetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 밤에 구운 움짤을 상훈님이 눈으로 보고 판정하는 일 — <b>공개 전에</b>.
 *
 * <h3>★★ v1 과 뒤바뀐 것 — 이제 판정이 공개를 결정한다</h3>
 * v1 은 굽자마자 열고("검수 전 지급") 판정은 기록으로만 쌓았다. PR-7 에서 정본 순서로 돌렸다 —
 * <b>밤에 굽고 → 검수하고 → 아침에 도착</b>(정본 2·6장). 검수 창이 23:00~10:00 이라
 * 사용자가 갇히지 않고, 10:00 을 넘겨 판정되면 그날 낮에 도착한다(정본 16장).
 *
 * <h3>판정 두 개는 계속 나란히 쌓인다</h3>
 * 기계 게이트({@code gateVerdict})와 사람({@code humanVerdict})을 한 칸에 몰지 않는다. 그 둘의 일치율이
 * "PASS 는 사람 없이 지급" 으로 넘어갈 시점을 <b>감이 아니라 숫자로</b> 정해 준다({@link ZzalMotion} 주석).
 *
 * <h3>★ 실험 판정 원장과 절대 섞지 않는다</h3>
 * 여기는 <b>운영</b>이다. 모양이 비슷하다고 실험 쪽 도구(/judge)와 합치면, 한쪽 기준을 고칠 때
 * 다른 쪽이 조용히 따라 바뀐다(2026-09-03 지시).
 */
@Service
public class AdminService {

    private static final Logger log = LoggerFactory.getLogger(AdminService.class);

    private final AdminGuard adminGuard;
    private final ZzalMotionRepository motionRepository;
    private final ZzalPetRepository petRepository;
    private final GenJobRepository jobRepository;
    private final MotionCatalog catalog;
    private final S3Service s3Service;
    private final int localRegenMax;

    public AdminService(AdminGuard adminGuard,
                        ZzalMotionRepository motionRepository,
                        ZzalPetRepository petRepository,
                        GenJobRepository jobRepository,
                        MotionCatalog catalog,
                        S3Service s3Service,
                        @Value("${app.zzal.night.local-regen-max:2}") int localRegenMax) {
        this.adminGuard = adminGuard;
        this.motionRepository = motionRepository;
        this.petRepository = petRepository;
        this.jobRepository = jobRepository;
        this.catalog = catalog;
        this.s3Service = s3Service;
        this.localRegenMax = localRegenMax;
    }

    /**
     * 검수 대기 목록 — {@code REVIEW} 인 것만, 오래된 순.
     *
     * ★ v1 은 "사람 판정이 안 찍힌 행" 을 다 긁어 굽는 중·실패한 자리까지 딸려 왔다. 이제 상태로 고른다 —
     *   볼 그림이 없는 것을 목록에 띄우면 상훈님이 빈 칸 앞에서 판단할 수 없는 판정을 강요당한다.
     */
    @Transactional(readOnly = true)
    public List<AdminResponses.Pending> pending(Long userId) {
        adminGuard.require(userId);
        return motionRepository.findByStatusOrderByIdAsc(MotionStatus.REVIEW).stream()
                .map(m -> AdminResponses.Pending.from(m, label(m)))
                .toList();
    }

    /**
     * 판정을 받아 적고 <b>다음 상태로 옮긴다.</b>
     *
     * <ul>
     *   <li>{@code OK} → {@code OPEN}. 아직 화면에 뜨는 건 아니다 — 펫이 깨어 있는 첫 정산에 도착한다</li>
     *   <li>{@code REGENERATE} → 재생성 한도가 남았으면 {@code LOCAL_REQUESTED}(맥미니), 다 썼으면 {@code FAILED}.
     *       {@code nightOf} 는 그대로 둔다 — 다음 밤 계획이 FAILED 를 다시 올리고, 이월 우선권도 그 밤으로 잡힌다</li>
     * </ul>
     *
     * ★ 이미 본 것을 다시 눌러도 막지 않는다. 잘못 누른 판정을 고칠 길이 없으면 틀린 채로 남고,
     *   그 틀린 값이 게이트 일치율을 오염시킨다. 단 이미 {@code OPEN} 인 것을 되돌리지는 않는다 —
     *   도감에서 칸이 사라지면 "배운 게 없어졌다" 가 되고, 그건 어설픈 그림보다 나쁘다.
     */
    @Transactional
    public void review(Long userId, Long motionId, HumanVerdict verdict, String note) {
        adminGuard.require(userId);
        ZzalMotion motion = motionRepository.findById(motionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "그 모션을 찾을 수 없어요"));
        Instant now = Instant.now();
        motion.review(verdict, note, now);

        if (verdict == HumanVerdict.OK) {
            motion.approve(now);
            log.info("검수 통과 — motionId={} 동작={} (아침 공개 대기)", motionId, motion.getName());
            return;
        }
        if (motion.isRevealed()) {
            // 이미 사용자에게 도착한 것은 되돌리지 않는다(위 주석). 판정만 기록으로 남는다.
            log.warn("이미 도착한 동작에 REGENERATE — motionId={} 기록만 남깁니다", motionId);
            return;
        }
        if (motion.getRegenRound() >= localRegenMax) {
            motion.markFailed();
            log.warn("재생성 한도({})를 다 썼다 — motionId={} 그 밤은 실패, 다음 밤에 다시", localRegenMax, motionId);
            return;
        }
        motion.requestLocalRegen();
        log.info("재생성 요청 — motionId={} {}번째", motionId, motion.getRegenRound());
    }

    /**
     * 맥미니(codex) 러너가 폴링해 갈 주문 목록.
     *
     * ★ 시트·정체성 문단·지시문 본문을 함께 실어 보낸다 — 맥미니가 레포도 DB 도 안 봐도 되게.
     *   펫이 사라졌거나 지시문이 없는 주문은 목록에서 빼고 로그만 남긴다(러너가 빈손으로 헤매지 않게).
     */
    @Transactional(readOnly = true)
    public List<AdminResponses.RegenRequest> regenRequests(Long userId) {
        adminGuard.require(userId);
        List<ZzalMotion> rows = motionRepository.findByStatusOrderByIdAsc(MotionStatus.LOCAL_REQUESTED);
        Map<Long, ZzalPet> pets = petRepository.findAllById(rows.stream().map(ZzalMotion::getPetId).distinct().toList())
                .stream().collect(java.util.stream.Collectors.toMap(ZzalPet::getId, p -> p));
        return rows.stream().map(m -> {
            ZzalPet pet = pets.get(m.getPetId());
            if (pet == null) {
                log.warn("재생성 주문의 펫이 없다 — motionId={} petId={}", m.getId(), m.getPetId());
                return null;
            }
            try {
                return new AdminResponses.RegenRequest(m.getId(), pet.getId(),
                        pet.getSheetImageKey(), pet.getIdentityText(),
                        m.getName(), catalog.block(m.getName()), m.getRegenRound());
            } catch (RuntimeException e) {
                log.error("재생성 주문의 지시문을 못 읽었다 — motionId={} key={}", m.getId(), m.getName(), e);
                return null;
            }
        }).filter(java.util.Objects::nonNull).toList();
    }

    /**
     * 맥미니가 올린 결과를 등록한다 → 다시 <b>검수 대기</b>.
     *
     * ★ 곧바로 열지 않는다. 다시 구운 것도 사람이 한 번 본다 — 그게 "검수 후 공개" 다.
     * ★ 재생성을 요청한 자리가 아니면 거절한다({@code ZZAL_REGEN_NOT_REQUESTED}) — 아무 모션에나
     *   그림을 밀어 넣을 수 있으면 관리자 계정 하나가 도감을 통째로 바꿔 쓸 수 있다.
     */
    @Transactional
    public void upload(Long userId, Long motionId, String imageKey) {
        adminGuard.require(userId);
        ZzalMotion motion = motionRepository.findById(motionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "그 모션을 찾을 수 없어요"));
        if (motion.getStatus() != MotionStatus.LOCAL_REQUESTED) {
            throw new BusinessException(ErrorCode.ZZAL_REGEN_NOT_REQUESTED);
        }
        // 부화 때와 같은 문 — 내 키인지·이미 쓴 키인지 여기서 판정된다.
        s3Service.consume(userId, imageKey, Instant.now());
        motion.uploadedLocal(imageKey);
        log.info("맥미니 재생성 등록 — motionId={} {}번째 (검수 대기)", motionId, motion.getRegenRound());
    }

    /** 그 밤 현황 — 모션 행을 직접 센다(밤 기록의 숫자는 "집기 완료" 라 실제와 다르다, B52). */
    @Transactional(readOnly = true)
    public AdminResponses.NightSummary nightSummary(Long userId, LocalDate nightOf) {
        adminGuard.require(userId);
        List<ZzalMotion> rows = motionRepository.findByNightOf(nightOf);
        BigDecimal cost = rows.isEmpty()
                ? BigDecimal.ZERO
                : jobRepository.sumCostByMotionIds(rows.stream().map(ZzalMotion::getId).toList());
        return new AdminResponses.NightSummary(nightOf,
                count(rows, MotionStatus.QUEUED),
                count(rows, MotionStatus.BAKING),
                count(rows, MotionStatus.REVIEW),
                count(rows, MotionStatus.LOCAL_REQUESTED),
                count(rows, MotionStatus.OPEN),
                count(rows, MotionStatus.FAILED),
                cost);
    }

    private static long count(List<ZzalMotion> rows, MotionStatus status) {
        return rows.stream().filter(m -> m.getStatus() == status).count();
    }

    /** 카탈로그 밖 이름(v1 행)이면 key 를 그대로 보여 준다. */
    private String label(ZzalMotion m) {
        return catalog.byKey(m.getName()).map(MotionSpec::label).orElse(m.getName());
    }
}
