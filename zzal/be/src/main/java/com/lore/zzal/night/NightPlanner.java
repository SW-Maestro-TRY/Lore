package com.lore.zzal.night;

import com.lore.zzal.motion.MotionCatalog;
import com.lore.zzal.motion.MotionLayer;
import com.lore.zzal.motion.MotionSpec;
import com.lore.zzal.motion.MotionStatus;
import com.lore.zzal.motion.ZzalMotion;
import com.lore.zzal.motion.ZzalMotionRepository;
import com.lore.zzal.pet.ZzalPet;
import com.lore.zzal.pet.ZzalRules;
import com.lore.zzal.piece.PieceService;
import com.lore.zzal.piece.ZzalPiece;
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
 * <h3>여기서 오르는 것</h3>
 * <ul>
 *   <li><b>지난 밤 실패(FAILED)</b> — 조각을 소모하지 않고 다시(16장)</li>
 *   <li><b>첫 심화 행동(뒤로 넘어짐)</b> — 함께한 날 3 이상 + 그날 케어 미스 0. 놓치면 다음에 같은 판정(16장)</li>
 *   <li><b>3층 차례</b> — 조각 네 칸이 다 찼을 때(정본 6장 · 1.9)</li>
 * </ul>
 *
 * <h3>★ 구르기는 여기 없다</h3>
 * 튜토리얼 9칸 완주 보상이라 잠과 무관하다 — {@code BakeTrigger.onTutorialDone} 이 맡는다(1.7).
 *
 * <h3>★ 부르는 자리가 바뀌었다</h3>
 * 판정은 그대로지만, 이제 <b>조건을 채운 그 순간</b> {@code BakeTrigger} 가 부른다(1.8).
 * 23:00 스위프도 여전히 부르지만 그건 놓친 것을 줍는 그물이다.
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
    private final PieceService pieceService;

    public NightPlanner(ZzalMotionRepository motionRepository, MotionCatalog catalog, PieceService pieceService) {
        this.motionRepository = motionRepository;
        this.catalog = catalog;
        this.pieceService = pieceService;
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

        // 2) 첫 심화 행동(뒤로 넘어짐) — 함께한 날 3 + 그날 케어 미스 0 (정본 6·16장).
        //
        // ★★ 1.7 정정 — 여기가 오래 <b>구르기</b>(선물 0번)를 주고 있었다. 정본은 구르기를
        //    <b>튜토리얼 9칸 완주</b> 보상으로 정했고(1.2 결정 · 1.7 표 반영), 함께한 날 3일 조건은
        //    <b>뒤로 넘어짐</b>(선물 1번) 것이다. 옛 코드대로면 튜토리얼 완주 보상이 첫날이 아니라
        //    사흘 뒤에 오고, 그 사람은 그런 것이 있는 줄도 모른 채 이틀을 보낸다.
        //    구르기는 이제 {@code BakeTrigger.onTutorialDone} 이 맡는다.
        ZzalMotion gift2 = catalog.gifts().size() > 1 ? rows.get(catalog.gifts().get(1).seq()) : null;
        if (gift2 != null && gift2.getStatus() == MotionStatus.NONE
                && pet.getDaysTogether() >= ZzalRules.FIRST_GIFT_DAYS
                && pet.getLastNightCareMiss() == 0
                && nightOf.equals(pet.getLastNightOf())) {
            if (catalog.isBakeable(gift2.getName())) {
                gift2.queue(nightOf);
                queued++;
                log.info("첫 심화 행동 큐 등록 — petId={} nightOf={} key={} ({}일째)",
                        pet.getId(), nightOf, gift2.getName(), pet.getDaysTogether());
            } else {
                log.info("첫 심화 조건은 찼지만 지시문이 없어 안 굽는다 — petId={} key={} (app.zzal.gift-motions)",
                        pet.getId(), gift2.getName());
            }
        }
        // 3) 3층 — 조각 네 칸이 다 찼으면 다음 심화 하나(정본 6장 · 1.9)
        //
        // ★ 1.9 에서 "이틀 연속" 이 없어졌다. 요구량 자체가 이틀치라 연속을 셀 이유가 없다.
        // ★★★ 완성을 <b>소모</b>한다(piece.consume). 옛 코드의 consumePieceStreak 이 하던 일이다.
        //    안 그러면 직접 재워 한 번 걸고 같은 밤 스위프(NightSweep.planAll)가 또 걸어
        //    <b>심화 둘이 구워진다</b> — 판은 다음 기상에야 비워지므로 그때까지 isComplete 가 계속 참이다.
        //
        // ★★ 여기에는 <b>"그 밤이 지금 밤인가"({@code nightOf.equals(lastNightOf)}) 조건이 없다.</b>
        //    2)번과 달라 보이지만 그게 맞다 — 2)번은 <b>잠들 때 스냅샷된</b> lastNightCareMiss 를 읽으므로
        //    그 스냅샷이 이 밤 것인지 확인해야 하고, 3)번이 읽는 것은 조각 네 칸뿐이라 밤과 무관하다.
        //    실제로 그 조건이 여기 붙어 있던 동안 <b>낮에는 절대 굽지 않았다</b>: 조각은 낮에 차고
        //    {@code dateOf(지금)} 은 오늘인데 {@code lastNightOf} 는 <b>어젯밤</b>이라 영원히 같지 않다.
        //    (2026-09-11 실측 — 네 칸을 다 채웠는데 큐가 비어 있었다. 시험은 목으로 밤 시각을 주고 있어 못 봤다.)
        //    두 번 굽는 길은 {@code consume()} 이 막는다 — 날짜가 아니라 소모가 막는 것이 맞다.
        ZzalPiece piece = pieceService.find(pet.getId());
        if (pet.isPiecesEnabled() && piece != null && piece.isComplete() && !piece.isConsumed()) {
            ZzalMotion next = nextAdvanced(rows);
            if (next != null) {
                next.queue(nightOf);
                piece.consume();
                queued++;
                log.info("3층 심화 큐 등록 — petId={} nightOf={} seq={} key={}",
                        pet.getId(), nightOf, next.getSeq(), next.getName());
            } else {
                log.info("조각은 찼지만 구울 심화가 없다 — petId={} (app.zzal.advanced-motions 확인)", pet.getId());
            }
        }

        // ★ 옛 4)번 "두 번째 선물 — 3층 심화 8종 뒤" 블록은 없앴다(1.7).
        //   선물은 둘뿐이고 각자 제 조건이 있다 — 구르기는 튜토리얼 완주, 뒤로 넘어짐은 위 2)번이다.
        //   8종 뒤에 또 주면 한 사람이 뒤로 넘어짐을 두 번 받는다.
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

    /** 첫 선물 spec(구르기). 순서는 16장 기본값 "구르기 먼저". */
    MotionSpec firstGift() {
        return catalog.gifts().get(0);
    }

    /** 이 밤에 굽기 후보 전부(이월 포함) — 스위프가 우선순위를 매긴다. */
    public List<ZzalMotion> queued() {
        return motionRepository.findByStatusOrderByIdAsc(MotionStatus.QUEUED);
    }
}
