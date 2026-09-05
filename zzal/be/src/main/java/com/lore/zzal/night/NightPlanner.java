package com.lore.zzal.night;

import com.lore.zzal.motion.MotionCatalog;
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
        return queued;
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
