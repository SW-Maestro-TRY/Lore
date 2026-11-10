package com.lore.zzal.chat.presence;

import com.lore.common.exception.*;
import com.lore.zzal.chat.ChatService;
import com.lore.zzal.chat.dto.ChatResponses;
import com.lore.zzal.pet.ZzalPetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import java.time.*;
import java.util.Objects;

/** Only confirmed service departures qualify. No timeout is invented for lost leave beacons. */
@Service
public class ChatPresenceService {
    public static final Duration RETURN_AFTER=Duration.ofHours(1);
    private final ZzalPetRepository pets;
    private final ZzalChatVisitRepository visits;
    private final ZzalChatPresenceReceiptRepository receipts;
    private final ChatService chat;
    private final JsonMapper json;
    @org.springframework.beans.factory.annotation.Autowired private com.lore.zzal.diary.DiaryService diary;
    public ChatPresenceService(ZzalPetRepository pets,ZzalChatVisitRepository visits,
            ZzalChatPresenceReceiptRepository receipts,ChatService chat,JsonMapper json) {
        this.pets=pets;this.visits=visits;this.receipts=receipts;this.chat=chat;this.json=json;
    }
    @Transactional
    public ChatPresenceResponse accept(Long userId,Long petId,ChatPresenceRequest request,Instant now) {
        return accept(userId,petId,request,now,null);
    }
    @Transactional
    public ChatPresenceResponse accept(Long userId,Long petId,ChatPresenceRequest request,Instant now,String language) {
        var pet=pets.findByIdForUpdate(petId).filter(p->p.isOwnedBy(userId))
                .orElseThrow(()->new BusinessException(ErrorCode.ZZAL_PET_NOT_FOUND));
        String encoded=json.writeValueAsString(request);
        var previous=receipts.findByPetIdAndEventId(petId,request.eventId());
        if(previous.isPresent()) {
            if(!previous.get().matches(encoded)) throw new BusinessException(ErrorCode.ZZAL_CHAT_REQUEST_CONFLICT);
            var saved=json.readValue(previous.get().responseJson(),ChatPresenceResponse.class);
            if(pet.getFirstNightChatDeadlineAt()==null)return saved;
            pet.settle(pet.now(now));
            // A duplicate event only confirms its old identity. Do not expose a stale speech after grace expiry.
            if(pet.isSleeping()||pet.isTraveling())return new ChatPresenceResponse("UNAVAILABLE",null);
            return saved;
        }
        var visit=visits.findByPetIdAndVisitId(petId,request.visitId())
                .orElseGet(()->new ZzalChatVisit(petId,request.visitId()));
        ChatPresenceResponse response;
        boolean wasActive=visits.findByPetId(petId).stream().anyMatch(ZzalChatVisit::active);
        if(request.sequence()<=visit.sequence()) response=new ChatPresenceResponse("STALE",null);
        else if(request.event()==ChatPresenceRequest.Event.LEAVE) {
            visit.leave(request.sequence(),now);visits.save(visit);
            response=new ChatPresenceResponse("LEFT",null);
        } else {
            pet.settle(pet.now(now));
            if(language!=null)pet.diaryLanguage(language);
            var all=visits.findByPetId(petId);
            boolean anyActive=all.stream().anyMatch(ZzalChatVisit::active);
            Instant left=all.stream().map(ZzalChatVisit::leftAt).filter(Objects::nonNull)
                    .max(Instant::compareTo).orElse(null);
            boolean qualifies=!anyActive&&left!=null&&!now.isBefore(left.plus(RETURN_AFTER));
            visit.enter(request.sequence());visits.save(visit);
            if(pet.isTraveling()||pet.isSleeping()||!pet.isFirstMeetingComplete())
                response=new ChatPresenceResponse("UNAVAILABLE",null);
            else {
                boolean generated=request.entryKind()!=ChatPresenceRequest.EntryKind.RELOAD&&qualifies
                        &&chat.appendReturnGreeting(userId,petId,now);
                var v=chat.continuousCalls(userId,petId,now);
                var current=new ChatResponses.Chat(v.openSlot(),v.calls().stream().map(ChatResponses.Call::from).toList(),
                        v.memories(),ChatResponses.Session.from(v.session()),v.turns().stream().map(ChatResponses.Turn::from).toList());
                response=new ChatPresenceResponse(generated?"GENERATED":request.entryKind()==ChatPresenceRequest.EntryKind.RELOAD
                        ?"IGNORED_RELOAD":"RESTORED",current);
            }
        }
        boolean isActive=visits.findByPetId(petId).stream().anyMatch(ZzalChatVisit::active);
        if(diary!=null)diary.presence(pet,wasActive,isActive,now);
        receipts.save(new ZzalChatPresenceReceipt(petId,request.eventId(),encoded,json.writeValueAsString(response),now));
        return response;
    }
}
