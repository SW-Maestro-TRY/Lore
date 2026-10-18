package com.lore.zzal.diary;

import com.lore.zzal.pet.*;
import com.lore.zzal.chat.memory.facts.FactMemoryService;
import com.lore.common.exception.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.PageRequest;
import tools.jackson.databind.json.JsonMapper;
import java.time.*;
import java.util.*;

@Service
public class DiaryService {
    private final ZzalPetRepository pets;
    private final DiaryEntryRepository entries;
    private final FactMemoryService memories;
    private final JsonMapper json;
    public DiaryService(ZzalPetRepository pets,DiaryEntryRepository entries,FactMemoryService memories,JsonMapper json){
        this.pets=pets;this.entries=entries;this.memories=memories;this.json=json;
    }
    /** Called only by confirmed presence transitions, under the existing pet row lock. */
    public void presence(ZzalPet pet,boolean wasActive,boolean isActive,Instant now){
        if(wasActive==isActive)return;
        Instant effective=pet.now(now);pet.settle(effective);
        if(isActive)pet.endDiaryAbsence();
        else pet.startDiaryAbsence(effective);
    }
    /** Separate transaction per pet; no generation occurs in a transaction or GET. */
    @Transactional
    public Optional<Long> reserveDue(Long petId,Instant real){
        var pet=pets.findByIdForUpdate(petId).orElse(null);
        if(pet==null)return Optional.empty();
        Instant now=pet.now(real);pet.settle(now);
        Instant at=pet.getDiaryOpportunityAt();
        if(pet.getDiaryAbsenceStartedAt()==null||at==null||pet.isDiaryOpportunityConsumed())return Optional.empty();
        // Even a blocked opportunity is consumed: unread time never becomes a backlog.
        pet.consumeDiaryOpportunity();
        if(pet.isInTutorial()||pet.isTraveling()||pet.getPhase()!=PetPhase.ALIVE||entries.existsByPetIdAndReadAtIsNull(petId))return Optional.empty();
        var snapshot=new LinkedHashMap<String,Object>();
        snapshot.put("sourceType","DIARY_FICTION");snapshot.put("subject","CHARACTER");snapshot.put("kind","FICTION");
        snapshot.put("opportunityAt",at);snapshot.put("name",pet.getName());snapshot.put("authoredPersona",pet.getAuthoredPersona());
        var legacy=new LinkedHashMap<String,Object>();legacy.put("note",pet.getNote());legacy.put("world",pet.getWorld());
        legacy.put("personalities",pet.getPersonalities());legacy.put("tone",pet.getTone());legacy.put("genre",pet.getGenre());
        snapshot.put("legacySource",legacy);snapshot.put("identity",pet.getIdentityText());
        snapshot.put("callMe","DEFERRED".equals(pet.getCallMeSource())?null:pet.getCallMe());snapshot.put("callMeSource",pet.getCallMeSource());
        snapshot.put("state",Map.of("fullness",pet.getFullness(),"happiness",pet.getHappiness(),"sleeping",pet.isSleeping(),"traveling",pet.isTraveling()));
        snapshot.put("backgroundKey",pet.getBackground()); // key only, no invented visual observation
        String identityHash=DiaryEntry.hash(Objects.toString(pet.getIdentityText(),""));
        snapshot.put("memories",memories.material(pet.getUserId(),petId,pet.getAuthoredPersona()==null?0:pet.getAuthoredPersona().version(),identityHash,now));
        String opportunity=petId+":"+pet.getDiaryAbsenceStartedAt();
        var entry=entries.save(DiaryEntry.reserve(petId,opportunity,at,now,pet.getDiaryLanguage(),json.writeValueAsString(snapshot)));
        return Optional.of(entry.id());
    }
    @Transactional(readOnly=true)
    public DiaryResponses.Page list(Long owner,Long petId,Long beforeId,int limit){
        owned(owner,petId);int size=Math.max(1,Math.min(100,limit));
        var anchor=beforeId==null?null:entries.findByIdAndPetId(beforeId,petId).filter(e->e.status()==DiaryEntry.Status.READY).orElseThrow(DiaryService::missing);
        var rows=anchor==null?entries.findByPetIdAndStatusOrderByWrittenAtDescIdDesc(petId,DiaryEntry.Status.READY,PageRequest.of(0,size+1))
                :entries.page(petId,DiaryEntry.Status.READY,anchor.writtenAt(),beforeId,PageRequest.of(0,size+1));
        boolean more=rows.size()>size;var shown=rows.subList(0,Math.min(size,rows.size()));
        int unread=(int)entries.countByPetIdAndStatusAndReadAtIsNull(petId,DiaryEntry.Status.READY);
        boolean pending=entries.existsByPetIdAndStatusInAndReadAtIsNull(petId,List.of(DiaryEntry.Status.RESERVED,DiaryEntry.Status.GENERATING));
        return new DiaryResponses.Page(shown.stream().map(DiaryResponses.Entry::from).toList(),more?shown.getLast().id():null,unread,pending);
    }
    @Transactional
    public DiaryResponses.Entry read(Long owner,Long petId,Long entryId,Instant now){
        pets.findByIdForUpdate(petId).filter(p->p.isOwnedBy(owner)).orElseThrow(DiaryService::missing);
        var e=entries.locked(entryId).filter(x->x.petId().equals(petId)&&x.status()==DiaryEntry.Status.READY).orElseThrow(DiaryService::missing);
        e.read(now);return DiaryResponses.Entry.from(e);
    }
    private void owned(Long owner,Long petId){pets.findById(petId).filter(p->p.isOwnedBy(owner)).orElseThrow(DiaryService::missing);}
    private static BusinessException missing(){return new BusinessException(ErrorCode.ZZAL_PET_NOT_FOUND);}
}
