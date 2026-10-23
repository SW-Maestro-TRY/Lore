package com.lore.zzal.pet.persona;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.zzal.chat.ChatService;
import com.lore.zzal.pet.PetService;
import com.lore.zzal.pet.ZzalPetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;

/** Address persistence commits even if the bounded generation attempt fails. */
@Service
public class FirstMeetingService {
    private final PetService pets;
    private final ZzalPetRepository repository;
    private final ChatService chat;
    public FirstMeetingService(PetService pets, ZzalPetRepository repository, ChatService chat) {
        this.pets = pets; this.repository = repository; this.chat = chat;
    }
    @org.springframework.beans.factory.annotation.Autowired
    private com.lore.zzal.chat.stage.StageSpeechTransactions stageReads;
    private FirstMeeting.View storedDiscovery(Long userId,Long petId,FirstMeeting.View view){
        var row=stageReads==null?null:stageReads.readDiscovery(userId,petId);
        return row==null?view:view.withDiscovery(row.status()==com.lore.zzal.chat.stage.ZzalStageSpeech.Status.READY?row.line():null,row.status().name(),row.status()==com.lore.zzal.chat.stage.ZzalStageSpeech.Status.READY?row.source():null);
    }
    @Transactional(readOnly = true)
    public FirstMeeting.View get(Long userId, Long petId) {
        var pet = pets.get(userId, petId);
        return storedDiscovery(userId,petId,FirstMeeting.View.of(pet).withGreeting(chat.firstMeetingGreeting(pet), chat.firstMeetingGreetingRetryAllowed(pet)));
    }
    @Transactional
    public FirstMeeting.View act(Long userId, Long petId, FirstMeeting.Request request, Instant now) {
        return act(userId, petId, request, now, null);
    }
    @Transactional
    public FirstMeeting.View act(Long userId, Long petId, FirstMeeting.Request request, Instant now, String language) {
        var pet = repository.findByIdForUpdate(petId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ZZAL_PET_NOT_FOUND));
        pets.get(userId, petId); // ownership is checked before any transition or generation
        if (!pet.isFirstMeetingComplete() && language != null) pet.diaryLanguage(language);
        pets.firstMeeting(userId, petId, request);
        if (!pet.isFirstMeetingComplete() && (request.action() == FirstMeeting.Action.SET_ADDRESS || request.action() == FirstMeeting.Action.DEFER_ADDRESS))
            chat.prepareFirstMeetingGreeting(pet, now);
        return FirstMeeting.View.of(pet).withGreeting(chat.firstMeetingGreeting(pet), chat.firstMeetingGreetingRetryAllowed(pet));
    }
}
