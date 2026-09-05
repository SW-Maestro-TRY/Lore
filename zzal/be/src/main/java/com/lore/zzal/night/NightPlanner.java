package com.lore.zzal.night;

import com.lore.zzal.motion.MotionCatalog;
import com.lore.zzal.motion.MotionLayer;
import com.lore.zzal.motion.MotionSpec;
import com.lore.zzal.motion.MotionStatus;
import com.lore.zzal.motion.ZzalMotion;
import com.lore.zzal.motion.ZzalMotionRepository;
import com.lore.zzal.pet.ZzalPet;
import com.lore.zzal.pet.ZzalRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 잠드는 순간 "이 밤에 무엇을 굽나" 를 정해 큐(QUEUED)에 올린다(정본 2·6·16장).
 *
 * <h3>지금 오르는 것(PR-6)</h3>
 * <ul>
 *   <li><b>첫 심화 행동(seq 101 구르기)</b> — 함께한 날 3 이상 + 그날 케어 미스 0. 놓치면 다음 밤에 같은 판정(16장)</li>
 *   <li><b>지난 밤 실패(FAILED)</b> — 조각을 소모하지 않고 다음 밤에 다시(16장 "굽기 실패는 조각을 소모하지 않는다")</li>
 * </ul>
 * 3층 조각(4개 이틀 연속 → 13장 순서)은 PR-10, 두 번째 선물은 3층 8번째 뒤.
 *
 * <h3>★ 지시문이 있는 것만 오른다</h3>
 * {@code app.zzal.advanced-motions}·{@code gift-motions} 에 없는 key 는 굽지 않는다(카탈로그 B12). 조건이 찼는데
 * 지시문이 없으면 로그만 남기고 다음 밤에 다시 본다 — 사용자 화면은 "아직 연습 중" 그대로.
 *
 * <h3>두 번 불러도 안전</h3>
 * 사용자 재우기와 23:00 스위프가 같은 펫에 둘 다 부를 수 있다. 이미 QUEUED·BAKING·REVIEW·OPEN 인 행은 건드리지 않는다.
 */
@Component
public class NightPlanner {

    private static final Logger log = LoggerFactory.getLogger(NightPlanner.class);

    private final ZzalMotionRepository motionRepository;
    private final MotionCatalog catalog;

    public NightPlanner(ZzalMotionRepository motionRepository, MotionCatalog catalog) {
        this.motionRepository = motionRepository;
        this.catalog = catalog;
    }

    /**
     * 이 펫의 이 밤 계획. 잠든 뒤(onSleep 훅이 돈 뒤)에 부른다 — {@code lastNightCareMiss} 가 그때 스냅샷된다.
     *
     * @return 새로 큐에 올린 행 수
     */
    @Transactional
    public int plan(ZzalPet pet, LocalDate nightOf) {
        if (!pet.isAlive()) {
            return 0;
        }
        // ★★ 여행 중에는 아무것도 굽지 않는다(#235 리뷰 하-1). 재등록(FAILED) 블록이 여행 중에도 돌면
        //   집을 비운 사람의 몫이 <b>매일 밤 API 로 구워진다</b> — 돈이 나가는 경로라 특히 조용히 아프다.
        //   여행 중에는 게이지도 조각도 안 도는데 굽기만 도는 것도 앞뒤가 안 맞는다.
        if (pet.isTraveling()) {
            return 0;
        }
        Map<Integer, ZzalMotion> rows = motionRepository.findByPetIdOrderBySeqAsc(pet.getId()).stream()
                .filter(m -> m.getLayer() != null)
                .collect(Collectors.toMap(ZzalMotion::getSeq, Function.identity(), (a, b) -> a));
        if (rows.isEmpty()) {
            return 0;   // v1 펫(18행 없음)은 밤 큐 대상이 아니다
        }
        int queued = 0;

        // 1) 지난 밤 실패 → 다시(같은 동작). 어느 밤이든 상관없다.
        for (ZzalMotion m : rows.values()) {
            if (m.getStatus() == MotionStatus.FAILED && catalog.isBakeable(m.getName())) {
                m.queue(nightOf);
                queued++;
            }
        }

        // 2) 첫 심화 행동 — 함께한 날 3 + 그날 케어 미스 0 (16장). 이미 오른/구운 것이면 건너뛴다.
        ZzalMotion gift = rows.get(catalog.gifts().get(0).seq());
        if (gift != null && gift.getStatus() == MotionStatus.NONE
                && pet.getDaysTogether() >= ZzalRules.FIRST_GIFT_DAYS
                && pet.getLastNightCareMiss() == 0
                && nightOf.equals(pet.getLastNightOf())) {
            if (catalog.isBakeable(gift.getName())) {
                gift.queue(nightOf);
                queued++;
                log.info("첫 심화 행동 큐 등록 — petId={} nightOf={} ({}일째)", pet.getId(), nightOf, pet.getDaysTogether());
            } else {
                log.info("첫 심화 조건은 찼지만 지시문이 없어 안 굽는다 — petId={} key={} (app.zzal.gift-motions)",
                        pet.getId(), gift.getName());
            }
        }
        // 3) 3층 — 조각 4개를 <b>이틀 연속</b> 모았으면 다음 심화 하나(정본 6장)
        if (pet.isPiecesEnabled() && pet.getLastNightPieceStreak() >= ZzalRules.PIECES_STREAK_TO_BAKE
                && nightOf.equals(pet.getLastNightOf())) {
            ZzalMotion next = nextAdvanced(rows);
            if (next != null) {
                next.queue(nightOf);
                pet.consumePieceStreak();
                queued++;
                log.info("3층 심화 큐 등록 — petId={} nightOf={} seq={} key={}",
                        pet.getId(), nightOf, next.getSeq(), next.getName());
            } else {
                log.info("조각은 찼지만 구울 심화가 없다 — petId={} (app.zzal.advanced-motions 확인)", pet.getId());
            }
        }

        // 4) 두 번째 선물(뒤로 넘어짐) — 3층 심화가 8종 열린 뒤(정본 6·16장)
        // ★ 3층 블록과 같은 가드 안에 둔다 — 3층이 열리지 않았거나 이 밤에 잠들지 않은 펫에게는
        //   어떤 심화도 오르면 안 된다(#234 리뷰 하).
        if (pet.isPiecesEnabled() && nightOf.equals(pet.getLastNightOf())
                && openedAdvanced(rows) >= ZzalRules.SECOND_GIFT_AFTER_ADVANCED && catalog.gifts().size() > 1) {
            ZzalMotion gift2 = rows.get(catalog.gifts().get(1).seq());
            if (gift2 != null && gift2.getStatus() == MotionStatus.NONE && catalog.isBakeable(gift2.getName())) {
                gift2.queue(nightOf);
                queued++;
                log.info("두 번째 선물 큐 등록 — petId={} nightOf={} key={}", pet.getId(), nightOf, gift2.getName());
            }
        }
        return queued;
    }

    /**
     * 다음에 구울 3층 심화 — <b>13장 번호 순</b>(정본 16장 "3층 심화 순서 = 13장 번호 순").
     *
     * ★ 선물(101·102)은 이 순서 밖이다 — 번호에 안 들어간다(16장). 지시문이 없는 동작은 건너뛴다.
     * ★ 이미 오른 것·굽는 중·검수 중·열린 것은 후보가 아니다. {@code FAILED} 도 여기서 안 집는다 —
     *   그건 위 1)이 이미 다시 올렸다(두 번 올리면 같은 밤에 두 번 굽는다).
     */
    private ZzalMotion nextAdvanced(Map<Integer, ZzalMotion> rows) {
        return rows.values().stream()
                .filter(m -> m.getLayer() != MotionLayer.GIFT)
                .filter(m -> m.getStatus() == MotionStatus.NONE)
                .filter(m -> catalog.isBakeable(m.getName()))
                .min(java.util.Comparator.comparingInt(ZzalMotion::getSeq))
                .orElse(null);
    }

    /** 3층 심화가 몇 종 열렸나(선물 제외). 두 번째 선물의 조건. */
    private static long openedAdvanced(Map<Integer, ZzalMotion> rows) {
        return rows.values().stream()
                .filter(m -> m.getLayer() != MotionLayer.GIFT)
                .filter(m -> m.getStatus() == MotionStatus.OPEN)
                .count();
    }

    /** 첫 선물 spec(구르기). 순서는 16장 기본값 "구르기 먼저". */
    MotionSpec firstGift() {
        return catalog.gifts().get(0);
    }

    /** 이 밤에 굽기 후보 전부(이월 포함) — 스위프가 우선순위를 매긴다. */
    public List<ZzalMotion> queued() {
        return motionRepository.findByStatusOrderByIdAsc(MotionStatus.QUEUED);
    }
}
