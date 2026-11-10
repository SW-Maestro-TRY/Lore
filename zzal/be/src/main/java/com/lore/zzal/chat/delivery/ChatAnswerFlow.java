package com.lore.zzal.chat.delivery;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.zzal.chat.ChatService;
import com.lore.zzal.chat.ChatSlot;
import com.lore.zzal.chat.ChatTurnCommit;
import com.lore.zzal.chat.dto.ChatRequests;
import com.lore.zzal.chat.dto.ChatResponses;
import com.lore.zzal.chat.line.GeneratedLine;
import com.lore.zzal.motion.MotionCatalog;
import com.lore.zzal.pet.PetService;
import com.lore.zzal.pet.ZzalPet;
import com.lore.zzal.pet.ZzalPetRepository;
import com.lore.zzal.pet.dto.PetResponses;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * 답하기(answer)를 T1 / 트랜잭션 밖 LLM / T2 로 나눈다(설계 4-1).
 *
 * <h3>왜</h3>
 * 예전에는 한 트랜잭션이 펫 행을 잠근 채 OpenAI 를 기다렸다(4초 × 최대 2회). 그동안 DB 연결 1개와 펫 잠금을
 * 쥐었고, 호출 기록(REQUIRES_NEW)이 연결을 하나 더 원해 동시 대화가 풀 크기에 닿으면 전체 API 가 교착됐다
 * (11/3 벤치: 채팅 동시 10명부터, 20명 판은 300건 중 206건 500).
 *
 * <h3>세 구간</h3>
 * <ol>
 *   <li><b>T1</b>(짧음): 펫 잠금 → 소유·요청 id 재전송·판·차례 검사 → LLM 문맥을 값으로 만든다 → <b>롤백</b>.
 *       문맥이 지금과 같도록 사용자 턴을 임시로 넣어 대화 기록을 읽지만 커밋하지 않는다. 검사에서 거절되면
 *       ({@link BusinessException}) 정산은 커밋한다 — 예전 {@code noRollbackFor} 와 같다</li>
 *   <li><b>밖</b>: LLM 호출. 연결도 잠금도 쥐지 않는다</li>
 *   <li><b>T2</b>(짧음): 펫 잠금 → 요청 id 재확인(먼저 저장된 배달이 있으면 그것을 돌려준다) → 차례 조건부 갱신 →
 *       턴·보상·세션 비용·배달 행 저장. 거절({@link BusinessException})이어도 정산은 커밋한다</li>
 * </ol>
 *
 * <h3>같은 차례의 동시 요청 — 기다렸다가 다시 본다</h3>
 * T1 은 (펫, 판, 차례)에 "진행 중" 표시를 남긴다. 같은 차례로 먼저 들어간 요청이 있으면 LLM 을 부르지 않고
 * 그 요청이 끝나기를 기다렸다가 T1 부터 다시 한다 — 같은 요청 id 면 저장된 응답을 돌려받고(재전송),
 * 다른 요청 id 면 차례가 이미 올라가 STALE 이다. 예전의 "펫 잠금에서 줄 서기" 와 같은 결과를 연결 없이 낸다.
 * ★ 이 표시는 이 서버 메모리에만 있다 — LLM 을 두 번 부르지 않기 위한 것일 뿐, 저장이 하나뿐인 것은
 *   T2 의 조건부 갱신과 배달 행 유일 제약이 보장한다(서버가 둘이어도 그렇다).
 */
@Component
public class ChatAnswerFlow {

    /** 같은 차례의 앞선 요청을 기다리는 상한 — LLM 4초 × 2회와 첫 만남 12초를 넉넉히 덮는다. */
    static final Duration WAIT_FOR_SAME_ROUND = Duration.ofSeconds(30);
    private static final int MAX_ROUNDS_OF_WAITING = 3;

    private final ChatService chat;
    private final ZzalPetRepository pets;
    private final ZzalChatDeliveryRepository deliveries;
    private final PetService petService;
    private final MotionCatalog catalog;
    private final JsonMapper json;
    private final TransactionTemplate tx;
    private final EntityManagerFactory entityManagers;
    private final ConcurrentHashMap<String, InFlight> inFlight = new ConcurrentHashMap<>();

    public ChatAnswerFlow(ChatService chat, ZzalPetRepository pets, ZzalChatDeliveryRepository deliveries,
                          PetService petService, MotionCatalog catalog, JsonMapper json,
                          PlatformTransactionManager transactions, EntityManagerFactory entityManagers) {
        this.chat = chat;
        this.pets = pets;
        this.deliveries = deliveries;
        this.petService = petService;
        this.catalog = catalog;
        this.json = json;
        this.tx = new TransactionTemplate(transactions);
        this.entityManagers = entityManagers;
    }

    /** 같은 차례로 진행 중인 요청 하나. 끝나면 {@link #done} 이 열린다. */
    private record InFlight(UUID requestId, CountDownLatch done) {
    }

    /** T1 의 결과 — 재전송 응답, 앞선 요청 기다리기, 또는 LLM 에 넘길 값 중 하나. */
    private record Prepared(ChatResponses.Answered replay, InFlight waitFor, ChatTurnCommit commit, String key,
                            InFlight mine) {
    }

    public ChatResponses.Answered answer(Long userId, Long petId, ChatSlot slot, ChatRequests.Answer request,
                                        Instant now) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            // 바깥 트랜잭션에 합류하면 T1 롤백이 바깥까지 번지고, LLM 대기 내내 그 연결을 쥔다.
            throw new IllegalStateException("답하기는 트랜잭션 밖에서 불러야 한다(T1/T2 를 스스로 연다)");
        }
        // ★★ 요청 단위 EntityManager(open-in-view)를 이 흐름 동안 떼어 둔다. 붙어 있으면 T1 에서 잡은 JDBC 연결을
        //   요청이 끝날 때까지 놓지 않아(연결 처리 방식 DELAYED_ACQUISITION_AND_HOLD) LLM 대기 중에도 쥔다 —
        //   P4-1 시험이 이 상태에서 빨강이었다. 떼어 두면 T1·T2 가 각자 EntityManager 를 열고 닫으며 연결을 돌려준다.
        //   응답은 값(DTO)으로만 만들므로 요청 끝의 지연 로딩이 필요 없다.
        Object requestScoped = TransactionSynchronizationManager.unbindResourceIfPossible(entityManagers);
        try {
            return run(userId, petId, slot, request, now);
        } finally {
            if (requestScoped != null) TransactionSynchronizationManager.bindResource(entityManagers, requestScoped);
        }
    }

    private ChatResponses.Answered run(Long userId, Long petId, ChatSlot slot, ChatRequests.Answer request,
                                       Instant now) {
        long started = System.nanoTime();
        for (int round = 0; ; round++) {
            final int waited = round;
            Prepared p = inTransaction(status -> prepare(status, userId, petId, slot, request, now, waited));
            if (p.replay() != null) return p.replay();
            if (p.waitFor() != null) {
                awaitQuietly(p.waitFor());
                continue;
            }
            try {
                GeneratedLine g = chat.generateReply(p.commit());          // ── 트랜잭션 밖 ──
                return inTransaction(status -> save(userId, petId, slot, request, now, started, p, g));
            } finally {
                inFlight.remove(p.key(), p.mine());
                p.mine().done().countDown();
            }
        }
    }

    // ── T1 ───────────────────────────────────────────────────────────────

    private Prepared prepare(TransactionStatus status, Long userId, Long petId, ChatSlot slot,
                             ChatRequests.Answer request, Instant now, int round) {
        // 같은 펫의 조회/답과 같은 잠금. 소유권은 재전송에서도 검사하고 다른 사용자의 응답을 내주지 않는다.
        pets.findByIdForUpdate(petId).filter(p -> p.isOwnedBy(userId))
                .orElseThrow(() -> new BusinessException(ErrorCode.ZZAL_PET_NOT_FOUND));
        if (request.protectedDelivery()) {
            var previous = deliveries.findByPetIdAndRequestId(petId, request.requestId());
            if (previous.isPresent()) {
                return new Prepared(replay(previous.get(), userId, petId, slot, request, now), null, null, null, null);
            }
        }
        if (slot != ChatSlot.CONTINUOUS) throw new BusinessException(ErrorCode.ZZAL_CHAT_SESSION_STALE);
        // 옛 클라이언트는 식별자가 없어 기존 계약을 유지한다. 멱등 보호는 새 계약에만 적용된다.
        if (request.tutorial() != null && !request.isDeliveryComplete())
            throw new BusinessException(ErrorCode.ZZAL_CHAT_REQUEST_CONFLICT);
        ChatTurnCommit c = request.tutorial() != null
                ? chat.prepareTutorialAnswer(userId, petId, request.text(), request.sessionId(), request.expectedRound(),
                        request.tutorial(), now)
                : request.protectedDelivery()
                ? chat.prepareTargetedAnswer(userId, petId, slot, request.text(), request.sessionId(),
                        request.expectedRound(), now)
                : chat.prepareLegacyAnswer(userId, petId, request.text(), now);
        // ★ T1 은 쓰기를 남기지 않는다 — 문맥을 만들려고 넣은 사용자 턴·차례·호칭은 T2 가 다시 한다.
        status.setRollbackOnly();
        String key = petId + "/" + c.sessionId() + "/" + c.expectedRound();
        InFlight mine = new InFlight(request.requestId(), new CountDownLatch(1));
        InFlight ahead = inFlight.putIfAbsent(key, mine);
        if (ahead != null && round < MAX_ROUNDS_OF_WAITING) {
            return new Prepared(null, ahead, null, null, null);
        }
        if (ahead != null) {
            // 기다려도 끝나지 않았다 — 표시 없이 진행한다. 저장 하나는 T2 의 조건부 갱신이 지킨다.
            mine = new InFlight(request.requestId(), new CountDownLatch(1));
            key = key + "#" + UUID.randomUUID();
            inFlight.put(key, mine);
        }
        return new Prepared(null, null, c, key, mine);
    }

    // ── T2 ───────────────────────────────────────────────────────────────

    private ChatResponses.Answered save(Long userId, Long petId, ChatSlot slot, ChatRequests.Answer request,
                                        Instant now, long started, Prepared p, GeneratedLine g) {
        var locked = pets.findByIdForUpdate(petId).filter(x -> x.isOwnedBy(userId))
                .orElseThrow(() -> new BusinessException(ErrorCode.ZZAL_PET_NOT_FOUND));
        // A request accepted just before the deadline may finish later. Preserve that one reply,
        // then settle at completion time so the returned pet cannot remain falsely awake.
        // 그 밖에는 예전처럼 요청 시각으로 정산·표시한다(정산은 시각이 뒤로 가면 아무것도 하지 않는다).
        Instant completedAt = locked.getFirstNightChatDeadlineAt() == null ? now
                : now.plusNanos(Math.max(0, System.nanoTime() - started));
        if (request.protectedDelivery()) {
            var previous = deliveries.findByPetIdAndRequestId(petId, request.requestId());
            if (previous.isPresent()) return replay(previous.get(), userId, petId, slot, request, completedAt);
        }
        ChatService.Answered a = chat.commitAnswer(p.commit(), g, completedAt);
        PetResponses.Detail pet = PetResponses.Detail.from(a.action().pet(), null, a.action().pet().now(completedAt),
                catalog, petService.motionRows(petId), a.action().justUnlocked(), false,
                petService.scenes(petId), petService.pieces(petId));
        ChatResponses.Answered response = new ChatResponses.Answered(pet,
                new ChatResponses.Reply(a.replyLine(), a.reactionKey()), ChatResponses.Session.from(a.session()),
                a.turns().stream().map(ChatResponses.Turn::from).toList());
        if (request.protectedDelivery()) {
            deliveries.save(new ZzalChatDelivery(petId, slot, request, json.writeValueAsString(response), now));
        }
        return response;
    }

    // ── 공통 ─────────────────────────────────────────────────────────────

    /** 저장된 말을 돌려준다. 예전 깨어 있음·유예 스냅샷이 아니라 지금 펫으로. 다시 만들거나 영수증을 고치지 않는다. */
    private ChatResponses.Answered replay(ZzalChatDelivery previous, Long userId, Long petId, ChatSlot slot,
                                          ChatRequests.Answer request, Instant at) {
        if (!previous.matches(slot, request)) throw new BusinessException(ErrorCode.ZZAL_CHAT_REQUEST_CONFLICT);
        var saved = json.readValue(previous.responseJson(), ChatResponses.Answered.class);
        ZzalPet current = petService.refresh(userId, petId, at);
        PetResponses.Detail detail = PetResponses.Detail.from(current, null, current.now(at), catalog,
                petService.motionRows(petId), java.util.List.of(), false, petService.scenes(petId), petService.pieces(petId));
        return new ChatResponses.Answered(detail, saved.chatReply(), saved.session(), saved.turns());
    }

    /**
     * 짧은 트랜잭션 하나. 거절({@link BusinessException})이면 그때까지의 정산을 커밋한 뒤 다시 던진다 —
     * 예전 {@code @Transactional(noRollbackFor = BusinessException.class)} 와 같은 뜻. 롤백 표시는 그대로 존중한다.
     */
    private <T> T inTransaction(Function<TransactionStatus, T> body) {
        BusinessException[] rejected = new BusinessException[1];
        T out = tx.execute(status -> {
            try {
                return body.apply(status);
            } catch (BusinessException e) {
                rejected[0] = e;
                return null;
            }
        });
        if (rejected[0] != null) throw rejected[0];
        return Objects.requireNonNull(out);
    }

    private static void awaitQuietly(InFlight ahead) {
        try {
            ahead.done().await(WAIT_FOR_SAME_ROUND.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
