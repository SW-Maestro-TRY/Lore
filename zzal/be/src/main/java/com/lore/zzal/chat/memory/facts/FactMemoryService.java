package com.lore.zzal.chat.memory.facts;
import com.lore.common.exception.*;
import com.lore.zzal.pet.ZzalPetRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.Instant;
import java.util.*;

@Service
public class FactMemoryService {
    private final ZzalFactMemoryRepository facts;
    private final ZzalPetRepository pets;
    @org.springframework.beans.factory.annotation.Autowired private ZzalMemoryCaptureRepository captures;
    @org.springframework.beans.factory.annotation.Autowired private com.lore.zzal.chat.session.ZzalChatTurnRepository turns;
    public FactMemoryService(ZzalFactMemoryRepository facts,ZzalPetRepository pets){this.facts=facts;this.pets=pets;}
    /** Technical prompt budget, never a storage/history deletion rule. Basic lexical recall, not vector semantic search. */
    @Transactional(readOnly=true)
    public List<FactView> recall(Long petId,String userText){
        var candidates=new LinkedHashMap<Long,ZzalFactMemory>();
        if(userText!=null){
            Arrays.stream(userText.split("[^\\p{L}\\p{N}]+"))
                    .filter(t->t.length()>=2&&t.length()<=40).distinct().limit(6).forEach(token->
                    facts.findByPetIdAndStatusAndContentContainingIgnoreCaseOrderByIdDesc(petId,ZzalFactMemory.Status.ACTIVE,
                            token,PageRequest.of(0,20)).forEach(f->candidates.putIfAbsent(f.id(),f)));
        }
        facts.findByPetIdAndStatusOrderByIdDesc(petId,ZzalFactMemory.Status.ACTIVE,PageRequest.of(0,20))
                .forEach(f->candidates.putIfAbsent(f.id(),f));
        var selected=new ArrayList<FactView>();int chars=0;
        for(var fact:candidates.values()){
            int cost=fact.content().length()+fact.sourceQuote().length()+100;
            if(selected.size()>=20||chars+cost>4000)continue;
            selected.add(FactView.from(fact));chars+=cost;
        }
        return List.copyOf(selected);
    }
    /** Keep original history in the DB/UI, but do not re-inject invalidated source/reply pairs into the AI. */
    @Transactional(readOnly=true)
    public List<com.lore.zzal.chat.prompt.HistoryLine> filterHistory(Long petId,
            List<com.lore.zzal.chat.prompt.HistoryLine> history){
        if(history==null||history.isEmpty())return List.of();
        var ids=history.stream().map(com.lore.zzal.chat.prompt.HistoryLine::sourceTurnId).filter(Objects::nonNull).toList();
        if(ids.isEmpty())return history;
        var invalid=facts.findByPetIdAndSourceTurnIdInAndStatusIn(petId,ids,
                List.of(ZzalFactMemory.Status.SUPERSEDED,ZzalFactMemory.Status.RETRACTED));
        var blocked=new HashSet<Long>();
        // The RETRACT row is the user's current request, not an invalid old assertion.
        invalid.stream().filter(f->!f.operation().equals("RETRACT")).forEach(f->blocked.add(f.sourceTurnId()));
        if(!blocked.isEmpty())captures.findByPetIdAndUserTurnIdIn(petId,List.copyOf(blocked)).forEach(c->blocked.add(c.petTurnId()));
        var recent=turns.findByPetIdAndIdIn(petId,ids);
        var exposed=recent.stream().flatMap(t->t.recalledMemoryIds().stream()).distinct().toList();
        if(!exposed.isEmpty()){
            var retired=facts.findByPetIdAndIdInAndStatusIn(petId,exposed,
                    List.of(ZzalFactMemory.Status.SUPERSEDED,ZzalFactMemory.Status.RETRACTED))
                    .stream().map(ZzalFactMemory::id).collect(java.util.stream.Collectors.toSet());
            recent.stream().filter(t->t.isPet()&&t.recalledMemoryIds().stream().anyMatch(retired::contains))
                    .forEach(t->blocked.add(t.getId()));
        }
        return history.stream().filter(h->h.sourceTurnId()==null||!blocked.contains(h.sourceTurnId())).toList();
    }

    @Transactional(readOnly=true)
    public List<FactView> list(Long owner,Long petId,int limit){
        owned(owner,petId);return facts.findByPetIdOrderByIdDesc(petId,PageRequest.of(0,Math.max(1,Math.min(100,limit))))
                .stream().map(FactView::from).toList();
    }
    public record MaterialSnapshot(String state,Long petId,int personaVersion,String identityHash,long memoryRevision,
            Instant selectedAt,List<FactView> candidates) { }
    /** The caller must treat an exception as UNAVAILABLE, never as EMPTY. No generation or grants are performed. */
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public MaterialSnapshot material(Long owner,Long petId,int personaVersion,String identityHash,Instant at){
        owned(owner,petId);
        var rows=facts.findByPetIdAndStatusAndSubjectOrderByIdDesc(petId,ZzalFactMemory.Status.ACTIVE,
                ZzalFactMemory.Subject.CHARACTER,PageRequest.of(0,100));
        var candidates=rows.stream().filter(f->f.subject()==ZzalFactMemory.Subject.CHARACTER)
                .map(FactView::from).toList();
        long revision=facts.findTopByPetIdOrderByIdDesc(petId).map(ZzalFactMemory::id).orElse(0L);
        return new MaterialSnapshot(candidates.isEmpty()?(revision==0?"EMPTY":"NONE_ELIGIBLE"):"AVAILABLE",petId,personaVersion,identityHash,
                revision,at,candidates);
    }
    /** V3 custom materials: preserve subjects; only confirmed, source-backed reports. */
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public MaterialSnapshot sharedMaterial(Long owner,Long petId,int personaVersion,String identityHash,Instant at){
        owned(owner,petId);
        var candidates=facts.findByPetIdAndStatusOrderByIdDesc(petId,ZzalFactMemory.Status.ACTIVE,PageRequest.of(0,100))
                .stream().filter(FactMemoryService::eligibleSharedMaterial).map(FactView::from).toList();
        long revision=facts.findTopByPetIdOrderByIdDesc(petId).map(ZzalFactMemory::id).orElse(0L);
        return new MaterialSnapshot(candidates.isEmpty()?(revision==0?"EMPTY":"NONE_ELIGIBLE"):"AVAILABLE",petId,personaVersion,identityHash,revision,at,candidates);
    }
    static boolean eligibleSharedMaterial(ZzalFactMemory fact){
        return fact.status()==ZzalFactMemory.Status.ACTIVE
                && Set.of(ZzalFactMemory.Subject.USER,ZzalFactMemory.Subject.CHARACTER).contains(fact.subject())
                && fact.kind()==ZzalFactMemory.Kind.USER_REPORTED
                && fact.sourceTurnId()!=null && fact.sourceQuote()!=null && !fact.sourceQuote().isBlank();
    }
    private void owned(Long owner,Long pet){pets.findById(pet).filter(p->p.isOwnedBy(owner))
            .orElseThrow(()->new BusinessException(ErrorCode.ZZAL_PET_NOT_FOUND));}
}
