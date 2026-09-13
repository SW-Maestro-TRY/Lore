package com.lore.zzal.night;

import com.lore.zzal.motion.MotionCatalog;
import com.lore.zzal.motion.MotionService;
import com.lore.zzal.motion.MotionStatus;
import com.lore.zzal.motion.ZzalMotion;
import com.lore.zzal.motion.ZzalMotionRepository;
import com.lore.zzal.pet.AwakeClock;
import com.lore.zzal.pet.ZzalPet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.net.InetAddress;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * <b>조건을 채운 그 순간</b> 굽기를 시작한다(정본 1.8·1.9).
 *
 * <h3>★★ 무엇이 바뀌었나</h3>
 * 옛 모델은 <b>잠들 때 예약</b>하고 <b>23:00 스위프</b>가 한꺼번에 구웠다. 그러면 굽기가 밤에 몰려
 * 새벽 3~4시에나 끝나고, <b>판정이 다음 날로 밀린다.</b> 이제 조건을 채운 그 순간 굽기 시작해서
 * 하루 종일 흩어지고, 그날 안에 판정을 끝낼 수 있다.
 *
 * <pre>
 *   구르기        튜토리얼 9칸을 끝낸 순간
 *   뒤로 넘어짐    좌우 맞히기 한 판을 다 치고 못 이긴 순간
 *   3층 심화      네 번째 조각이 차는 순간 = 평소 돌보기 API 안에서
 * </pre>
 *
 * <h3>★★★ 왜 여기서 바로 집어(claim) 가나 — 돈이 두 번 나가는 길을 막는다</h3>
 * 큐에만 올리고 두면 <b>23:00 스위프가 같은 줄을 또 집는다.</b> 스위프는 {@code QUEUED} 를 집으므로,
 * 여기서 곧바로 {@code BAKING} 으로 집어 두면 그 길이 <b>물리적으로 막힌다</b> — 조건이 아니라 상태로 막는 것이
 * 안전하다. 조건은 나중에 누가 한 줄 더하면 뚫린다.
 *
 * <h3>★ 스위프를 없애지 않는다</h3>
 * 즉시 굽기가 놓친 것(서버가 죽어 있었거나, 집어 놓고 못 구운 것)을 줍는 <b>그물</b>로 남긴다.
 * 기본값은 꺼짐이고, 켜도 {@code QUEUED} 만 보므로 여기서 집어 간 줄은 건드리지 않는다.
 *
 * <h3>★ 굽기는 트랜잭션이 커밋된 뒤에 시작한다</h3>
 * 돌보기 API 안에서 불리므로, 커밋 전에 굽기를 던지면 굽는 스레드가 <b>아직 없는 줄</b>을 읽는다.
 * 실제로 그 순서가 어긋나면 "조각은 찼는데 아무것도 안 구워졌다" 가 되고, 조각은 이미 소모된 뒤다.
 */
@Component
public class BakeTrigger {

    private static final Logger log = LoggerFactory.getLogger(BakeTrigger.class);

    private final NightPlanner planner;
    private final ZzalMotionRepository motionRepository;
    private final MotionService motionService;
    private final MotionCatalog catalog;
    private final Executor nightExecutor;
    private final String server;

    public BakeTrigger(NightPlanner planner,
                       ZzalMotionRepository motionRepository,
                       MotionService motionService,
                       MotionCatalog catalog,
                       @Qualifier("nightExecutor") Executor nightExecutor) {
        this.planner = planner;
        this.motionRepository = motionRepository;
        this.motionService = motionService;
        this.catalog = catalog;
        this.nightExecutor = nightExecutor;
        this.server = hostname();
    }

    /**
     * 튜토리얼을 끝낸 순간 — 구르기(첫 선물)를 굽는다.
     *
     * ★ 정본 1.7 이 못박은 자리다. 옛 코드는 이걸 <b>함께한 날 3일</b>에 주고 있었는데,
     *   그러면 튜토리얼 완주 보상이 첫날에 안 오고 사흘 뒤에 온다 — 그 사람은 그런 것이 있는 줄도
     *   모른 채 이틀을 보낸다.
     */
    @Transactional
    public void onTutorialDone(ZzalPet pet, Instant now) {
        ZzalMotion gift = firstGiftRow(pet.getId());
        if (gift == null || gift.getStatus() != MotionStatus.NONE) {
            return;
        }
        if (!catalog.isBakeable(gift.getName())) {
            log.info("튜토리얼 완주 보상 조건은 찼지만 지시문이 없어 안 굽는다 — petId={} key={} (app.zzal.gift-motions)",
                    pet.getId(), gift.getName());
            return;
        }
        if (!gift.queue(AwakeClock.dateOf(now))) {
            return;     // 이미 굽는 중이거나 판정을 지난 자리 — 되돌리면 두 번 굽는다
        }
        log.info("튜토리얼 완주 보상 큐 등록 — petId={} key={}", pet.getId(), gift.getName());
        claimAndBake(List.of(gift), now);
    }

    /**
     * <b>좌우 맞히기에서 처음 진 순간</b> — 뒤로 넘어짐(두 번째 선물)을 굽는다.
     *
     * <h3>★ 왜 이 자리인가</h3>
     * 뒤로 넘어짐은 <b>패배 리액션</b>이다. 져서 충격받고 쓰러지는 그림이라, 진 그 순간에 받아야
     * 무엇의 선물인지가 사람에게 읽힌다. 옛 조건(함께한 날 3일)은 날짜였고 그림과 아무 관계가 없었다.
     *
     * <h3>★ "한 판" 은 5라운드를 다 친 판이다</h3>
     * 한 판이 곧 3선승 시리즈이므로 <b>한 라운드 패배는 패배가 아니다.</b> 부르는 쪽이
     * {@code guess()} 안에서만 부르므로 달리기도, 밤을 넘겨 접은 판({@code abandon})도 여기 안 온다 —
     * 접은 판이 패배로 세면 한 판도 끝까지 안 친 사람이 선물을 받고, 진 적이 없어 이유를 모른다.
     *
     * <h3>★ 화면 문구는 조건을 말하지 않는다</h3>
     * 잠긴 칸의 문구는 "언젠가 깜짝 선물" 그대로다. "게임에서 지면 준다" 는 선물이 아니게 된다.
     */
    @Transactional
    public void onFirstGameLoss(ZzalPet pet, Instant now) {
        ZzalMotion gift = secondGiftRow(pet.getId());
        if (gift == null || gift.getStatus() != MotionStatus.NONE) {
            return;     // 이미 굽는 중이거나 받은 사람 — 두 번 주지 않는다
        }
        if (!catalog.isBakeable(gift.getName())) {
            log.info("첫 패배 선물 조건은 찼지만 지시문이 없어 안 굽는다 — petId={} key={} (app.zzal.gift-motions)",
                    pet.getId(), gift.getName());
            return;
        }
        if (!gift.queue(AwakeClock.dateOf(now))) {
            return;
        }
        log.info("첫 패배 선물 큐 등록 — petId={} key={}", pet.getId(), gift.getName());
        claimAndBake(List.of(gift), now);
    }

    /**
     * 잠드는 순간 — 3층 차례를 본다.
     *
     * ★ 잠에 붙는 이유 — 밤 리듬(실패한 줄 다시 굽기)이 하루 한 번 도는 자리가 여기다.
     */
    @Transactional
    public void onSleep(ZzalPet pet, Instant now) {
        // ★ 밤 리듬 — 여기가 실패한 줄을 다시 굽는 하루 한 번의 자리다(NightPlanner.Occasion).
        int queued = planner.plan(pet, AwakeClock.dateOf(now), NightPlanner.Occasion.NIGHT);
        if (queued > 0) {
            claimAndBake(freshlyQueued(pet.getId()), now);
        }
    }

    /**
     * 네 번째 조각이 차는 순간 — 평소 돌보기 API 안에서 불린다.
     *
     * ★ 조각은 밥·게임·청소·채팅 안에서 차오른다. 그러니 굽기 시작도 그 안에서 일어나야
     *   "그 순간" 이 된다. 따로 훑는 배치를 두면 그 배치 주기만큼 늦어진다.
     *
     * <h3>★★★ 왜 여기만 {@code REQUIRES_NEW} 인가</h3>
     * 이 메서드는 {@link PieceCompletedListener} 가 <b>커밋 뒤에</b> 부른다. 그 시점의 트랜잭션은
     * 이미 끝나는 중이라, 보통의 {@code @Transactional}(REQUIRED) 로는 그것에 얹혀서
     * <b>바꾸는 질의가 "No active transaction" 으로 터진다</b> — 2026-09-11 실측에서 실제로 터졌다.
     * 큐 등록까지는 되고 집기(claim)에서 터져서, <b>QUEUED 인 채 아무도 안 굽는</b> 모습이 됐다.
     * 커밋이 이미 끝났으므로 여기서 새 트랜잭션을 여는 것은 잠금이 겹칠 일도 없다.
     *
     * ★ {@code onSleep}·{@code onTutorialDone} 은 반대다 — 돌보기 트랜잭션 <b>안에서</b> 불리므로
     *   그 트랜잭션에 얹혀야 하고, 여기서 새 트랜잭션을 열면 아직 커밋 안 된 펫을 못 본다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onPieceComplete(ZzalPet pet, Instant now) {
        // ★★ 여기는 낮에도 돈다 — 방금 찬 조각의 새 동작만 올리고, <b>실패한 줄은 건드리지 않는다.</b>
        //   같은 지시문으로 여러 번 굽는 것이 설계지만 그 리듬은 밤 한 번이다(NightPlanner.Occasion).
        int queued = planner.plan(pet, AwakeClock.dateOf(now), NightPlanner.Occasion.PIECE);
        if (queued > 0) {
            claimAndBake(freshlyQueued(pet.getId()), now);
        }
    }

    /** 이 펫의 큐에 올라 아직 안 집힌 줄들. */
    private List<ZzalMotion> freshlyQueued(Long petId) {
        return motionRepository.findByPetIdOrderBySeqAsc(petId).stream()
                .filter(m -> m.getStatus() == MotionStatus.QUEUED)
                .toList();
    }

    private ZzalMotion firstGiftRow(Long petId) {
        return giftRow(petId, 0);
    }

    private ZzalMotion secondGiftRow(Long petId) {
        return giftRow(petId, 1);
    }

    /** 선물 {@code index} 번째의 줄. 선물이 그만큼 없으면 null(설정으로 줄일 수 있다). */
    private ZzalMotion giftRow(Long petId, int index) {
        if (catalog.gifts().size() <= index) {
            return null;
        }
        int seq = catalog.gifts().get(index).seq();
        return motionRepository.findByPetIdOrderBySeqAsc(petId).stream()
                .filter(m -> m.getSeq() == seq)
                .findFirst()
                .orElse(null);
    }

    /**
     * 집어서 굽는다. <b>집기(claim)가 스위프와 겹치지 않게 하는 유일한 장치</b>다.
     *
     * ★ 커밋 뒤에 던지는 이유 — 굽는 스레드는 다른 트랜잭션이다. 커밋 전에 던지면
     *   아직 없는 줄을 읽고 "모션이 없습니다" 로 끝난다.
     * ★ 실행기가 가득 차면 굽기를 포기하고 <b>큐로 되돌린다.</b> 그러면 스위프가 나중에 줍는다 —
     *   여기서 그냥 버리면 그 줄은 BAKING 인 채 영영 남는다.
     */
    private void claimAndBake(List<ZzalMotion> rows, Instant now) {
        // ★ 스위프와 같은 문으로 집는다 — UPDATE ... WHERE status='QUEUED' 가 1 을 돌려줄 때만 내 것이다.
        //   엔티티로 상태만 바꾸면 두 곳이 동시에 "내가 집었다" 고 볼 수 있다.
        List<Long> ids = rows.stream()
                .filter(m -> m.getStatus() == MotionStatus.QUEUED)
                .map(ZzalMotion::getId)
                .filter(id -> motionRepository.claim(id, now, server) == 1)
                .toList();
        if (ids.isEmpty()) {
            return;
        }
        registerAfterCommit(() -> ids.forEach(this::submit));
    }

    private void submit(Long motionId) {
        try {
            nightExecutor.execute(() -> motionService.bakeNow(motionId));
        } catch (RejectedExecutionException e) {
            // ★ 여기서 그냥 버리면 그 줄은 BAKING 인 채 영영 남는다 — 다음 밤 계획은 NONE·FAILED 만 보고,
            //   스위프의 집기는 QUEUED 만 본다. 큐로 되돌려야 누군가 줍는다.
            int back = motionRepository.releaseClaim(motionId);
            log.warn("굽기 실행기가 받지 못했다 — motionId={} 집기를 되돌린다(되돌림={})", motionId, back, e);
        }
    }

    private static void registerAfterCommit(Runnable task) {
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

    private static String hostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
