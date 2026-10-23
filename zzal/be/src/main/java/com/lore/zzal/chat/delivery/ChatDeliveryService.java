package com.lore.zzal.chat.delivery;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.zzal.chat.ChatService;
import com.lore.zzal.chat.ChatSlot;
import com.lore.zzal.chat.dto.ChatRequests;
import com.lore.zzal.chat.dto.ChatResponses;
import com.lore.zzal.motion.MotionCatalog;
import com.lore.zzal.pet.PetService;
import com.lore.zzal.pet.ZzalPetRepository;
import com.lore.zzal.pet.dto.PetResponses;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;

@Service
public class ChatDeliveryService {
    private final ChatService chat;
    private final ZzalPetRepository pets;
    private final ZzalChatDeliveryRepository deliveries;
    private final PetService petService;
    private final MotionCatalog catalog;
    private final JsonMapper json;
    @org.springframework.beans.factory.annotation.Autowired
    private com.lore.zzal.chat.session.ZzalChatTurnRepository turns;
    @org.springframework.beans.factory.annotation.Autowired
    private com.lore.zzal.chat.ZzalChatCallRepository legacyCalls;

    public ChatDeliveryService(ChatService chat, ZzalPetRepository pets, ZzalChatDeliveryRepository deliveries,
                               PetService petService, MotionCatalog catalog, JsonMapper json) {
        this.chat = chat;
        this.pets = pets;
        this.deliveries = deliveries;
        this.petService = petService;
        this.catalog = catalog;
        this.json = json;
    }

    /** 화면 이력은 프롬프트 문맥과 별개다. 원문은 날짜 제한 없이 커서로 모두 읽을 수 있다. */
    @Transactional(readOnly = true)
    public ChatResponses.History history(Long userId, Long petId, Long beforeId, int limit) {
        pets.findById(petId).filter(p -> p.isOwnedBy(userId))
                .orElseThrow(() -> new BusinessException(ErrorCode.ZZAL_PET_NOT_FOUND));
        var rows = turns.findByPetIdAndIdLessThanOrderByIdDesc(petId, beforeId == null ? Long.MAX_VALUE : beforeId,
                org.springframework.data.domain.PageRequest.of(0, limit + 1));
        boolean more = rows.size() > limit;
        var visible = new java.util.ArrayList<>(rows.subList(0, Math.min(rows.size(), limit)));
        Long next = more ? visible.getLast().getId() : null;
        java.util.Collections.reverse(visible);
        return new ChatResponses.History(visible.stream().map(t -> new ChatResponses.HistoryTurn(t.getId(),
                t.getSessionId(), t.getIdx(), t.getSpeaker().name(), t.getLine(), t.getCreatedAt())).toList(), next);
    }

    /** 세션 도입 이전의 부름/답도 삭제하거나 이력 접근에서 빠뜨리지 않는다. */
    @Transactional(readOnly = true)
    public ChatResponses.LegacyHistory legacyHistory(Long userId, Long petId, Long beforeId, int limit) {
        pets.findById(petId).filter(p -> p.isOwnedBy(userId))
                .orElseThrow(() -> new BusinessException(ErrorCode.ZZAL_PET_NOT_FOUND));
        var rows = legacyCalls.findByPetIdAndIdLessThanOrderByIdDesc(petId,
                beforeId == null ? Long.MAX_VALUE : beforeId, org.springframework.data.domain.PageRequest.of(0, limit + 1));
        boolean more = rows.size() > limit;
        var visible = new java.util.ArrayList<>(rows.subList(0, Math.min(rows.size(), limit)));
        Long next = more ? visible.getLast().getId() : null;
        java.util.Collections.reverse(visible);
        return new ChatResponses.LegacyHistory(visible.stream().map(c -> new ChatResponses.LegacyHistoryCall(c.getId(),
                c.getSlot().name(), c.getLine(), c.getCalledAt(), c.getAnswer(), c.getAnsweredAt(), c.getReplyLine())).toList(), next);
    }

    /** Read stored receipts only. No presence/answer, reward or model call. */
    @Transactional(readOnly=true)
    public java.util.List<ChatResponses.TutorialAnswer> tutorialAnswers(Long userId,Long petId){
        pets.findById(petId).filter(p->p.isOwnedBy(userId)).orElseThrow(()->new BusinessException(ErrorCode.ZZAL_PET_NOT_FOUND));
        var result=new java.util.ArrayList<ChatResponses.TutorialAnswer>();
        for(var step:java.util.List.of(com.lore.zzal.chat.prompt.ConversationScene.Step.FEED,com.lore.zzal.chat.prompt.ConversationScene.Step.GAME)){
            var candidates=deliveries.latestTutorial(petId,ChatSlot.CONTINUOUS.name(),step.name(),org.springframework.data.domain.PageRequest.of(0,1));
            for(var row:candidates){var metadata=row.tutorial();if(metadata.isEmpty()||metadata.get().step()!=step)continue;
                var saved=json.readValue(row.responseJson(),ChatResponses.Answered.class);
                result.add(new ChatResponses.TutorialAnswer(row.requestId(),row.sessionId(),row.expectedRound(),step,metadata.get().answerType(),row.text(),saved.chatReply(),saved.turns()));
            }
        }
        return java.util.List.copyOf(result);
    }

    @Transactional(noRollbackFor = BusinessException.class)
    public ChatResponses.Answered answer(Long userId, Long petId, ChatSlot slot, ChatRequests.Answer request,
                                        Instant now) {
        // 같은 펫의 조회/답과 같은 잠금. 소유권은 재전송에서도 검사하고 다른 사용자의 응답을 내주지 않는다.
        long started = System.nanoTime();
        var locked = pets.findByIdForUpdate(petId).filter(p -> p.isOwnedBy(userId))
                .orElseThrow(() -> new BusinessException(ErrorCode.ZZAL_PET_NOT_FOUND));
        if (request.protectedDelivery()) {
            var previous = deliveries.findByPetIdAndRequestId(petId, request.requestId());
            if (previous.isPresent()) {
                if (!previous.get().matches(slot, request)) {
                    throw new BusinessException(ErrorCode.ZZAL_CHAT_REQUEST_CONFLICT);
                }
                var saved = json.readValue(previous.get().responseJson(), ChatResponses.Answered.class);
                // Recover the saved utterance, not its old awake/grace snapshot. Never regenerate or rewrite receipt.
                var current = petService.refresh(userId,petId,now);
                return new ChatResponses.Answered(currentDetail(current,petId,now),saved.chatReply(),saved.session(),saved.turns());
            }
        }
        if (slot != ChatSlot.CONTINUOUS) throw new BusinessException(ErrorCode.ZZAL_CHAT_SESSION_STALE);
        // 옛 클라이언트는 식별자가 없어 기존 계약을 유지한다. 멱등 보호는 새 계약에만 적용된다.
        if(request.tutorial()!=null && !request.isDeliveryComplete()) throw new BusinessException(ErrorCode.ZZAL_CHAT_REQUEST_CONFLICT);
        ChatService.Answered a = request.tutorial()!=null
                ? chat.answerTutorial(userId,petId,request.text(),request.sessionId(),request.expectedRound(),request.tutorial(),now)
                : request.protectedDelivery()
                ? chat.answerTargeted(userId, petId, slot, request.text(), request.sessionId(), request.expectedRound(), now)
                : chat.answerContinuousLegacy(userId, petId, request.text(), now);
        // A request accepted just before the deadline may finish later. Preserve that one reply,
        // then settle at completion time so the returned pet cannot remain falsely awake.
        Instant completedAt = locked.getFirstNightChatDeadlineAt() == null ? now
                : now.plusNanos(Math.max(0,System.nanoTime()-started));
        if (locked.getFirstNightChatDeadlineAt() != null) petService.refresh(userId,petId,completedAt);
        PetResponses.Detail pet = PetResponses.Detail.from(a.action().pet(), null, a.action().pet().now(completedAt), catalog,
                petService.motionRows(petId), a.action().justUnlocked(), false,
                petService.scenes(petId), petService.pieces(petId));
        ChatResponses.Answered response = new ChatResponses.Answered(pet,
                new ChatResponses.Reply(a.replyLine(), a.reactionKey()), ChatResponses.Session.from(a.session()),
                a.turns().stream().map(ChatResponses.Turn::from).toList());
        if (request.protectedDelivery()) {
            deliveries.save(new ZzalChatDelivery(petId, slot, request, json.writeValueAsString(response), now));
        }
        return response;
    }
    private PetResponses.Detail currentDetail(com.lore.zzal.pet.ZzalPet pet,Long petId,Instant realNow) {
        return PetResponses.Detail.from(pet,null,pet.now(realNow),catalog,petService.motionRows(petId),java.util.List.of(),false,
                petService.scenes(petId),petService.pieces(petId));
    }

}
