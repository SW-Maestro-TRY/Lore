package com.lore.zzal.chat.memory.facts;

import com.lore.zzal.chat.session.*;
import com.lore.zzal.pet.ZzalPetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import static com.lore.zzal.chat.memory.facts.ZzalFactMemory.*;

/** Pure local validation/storage: this worker never calls a model. */
@Service
public class MemoryCaptureWorker {
    private final ZzalMemoryCaptureRepository jobs;
    private final ZzalFactMemoryRepository facts;
    private final ZzalChatTurnRepository turns;
    private final ZzalPetRepository pets;
    private final ObjectMapper json=new ObjectMapper();
    public MemoryCaptureWorker(ZzalMemoryCaptureRepository jobs,ZzalFactMemoryRepository facts,
            ZzalChatTurnRepository turns,ZzalPetRepository pets){this.jobs=jobs;this.facts=facts;this.turns=turns;this.pets=pets;}
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void process(Long id){
        var job=jobs.findLocked(id).orElseThrow();
        if(!Set.of("QUEUED","FAILED").contains(job.status()))return;
        var pet=pets.findByIdForUpdate(job.petId()).orElseThrow();
        var user=turns.findById(job.userTurnId()).orElseThrow();
        var reply=turns.findById(job.petTurnId()).orElseThrow();
        if(!user.getPetId().equals(pet.getId())||!reply.getPetId().equals(pet.getId())||user.isPet()||!reply.isPet()){
            job.finish("INVALID","source_mismatch");return;
        }
        if(!Set.of("ok","retried_ok","truncated").contains(reply.getOutcome())){job.finish("INVALID","failed_reply");return;}
        JsonNode deltas;Set<Long> allowed=new HashSet<>();
        try{
            if(job.rawDeltas().length()>32768)throw new IllegalArgumentException();
            deltas=json.readTree(job.rawDeltas());
            if(!deltas.isArray()||deltas.size()>8)throw new IllegalArgumentException();
            for(var n:json.readTree(job.recalledIds()))allowed.add(n.asLong());
        }catch(Exception e){job.finish("INVALID","invalid_deltas");return;}
        int valid=0,bad=0;
        for(JsonNode d:deltas){
            try{
                String op=text(d,"operation",16),source=text(d,"source",8),quote=text(d,"source_quote",1000);
                if(!Set.of("ADD","CORRECT","RETRACT").contains(op))throw new IllegalArgumentException();
                var subject=Subject.valueOf(text(d,"subject",16));var kind=Kind.valueOf(text(d,"kind",16));
                boolean uncertain=!d.has("uncertain")||!d.path("uncertain").isBoolean()||d.path("uncertain").asBoolean();
                boolean durable=d.path("durable").isBoolean()&&d.path("durable").asBoolean();
                ZzalChatTurn origin=source.equals("USER")?user:source.equals("PET")?reply:null;
                if(origin==null||!origin.getLine().contains(quote))throw new IllegalArgumentException();
                if(source.equals("PET")&&(kind!=Kind.FICTION||subject!=Subject.CHARACTER))throw new IllegalArgumentException();
                if(kind==Kind.FICTION&&!source.equals("PET"))throw new IllegalArgumentException();
                String ref=optional(d,"subject_ref",100);
                if(subject==Subject.THIRD_PARTY&&(ref==null||ref.isBlank()))throw new IllegalArgumentException();
                if(subject==Subject.CHARACTER&&!"SELF".equals(ref))throw new IllegalArgumentException();
                Long correction=null;ZzalFactMemory target=null;
                if(!op.equals("ADD")){
                    var n=d.get("correction_of");
                    if(n==null||!n.isIntegralNumber()||!allowed.contains(n.asLong()))throw new IllegalArgumentException();
                    target=facts.findById(n.asLong()).orElseThrow();
                    if(!target.petId().equals(pet.getId())||!target.ownerId().equals(pet.getUserId())
                            ||!Set.of(Status.ACTIVE,Status.PENDING).contains(target.status())
                            ||!source.equals("USER")||kind!=Kind.USER_REPORTED||uncertain)throw new IllegalArgumentException();
                    correction=target.id();
                }
                String content=op.equals("RETRACT")?target.content():text(d,"content",1000);
                Status state=subject!=Subject.UNKNOWN&&kind==Kind.USER_REPORTED&&!uncertain&&durable?Status.ACTIVE:Status.PENDING;
                if(op.equals("RETRACT")){target.retract();state=Status.RETRACTED;}
                else if(target!=null)target.supersede();
                var saved=new ZzalFactMemory(pet.getUserId(),pet.getId(),subject,ref,content,kind,state,origin.getId(),quote,
                        job.at(),correction,target==null?1:target.revision()+1,job.version());
                saved.operation(op);facts.save(saved);
                valid++;
            }catch(IllegalArgumentException|NoSuchElementException invalid){bad++;}
        }
        job.finish(bad==0?(valid==0?"EMPTY":"APPLIED"):(valid==0?"INVALID":"PARTIAL"),bad==0?null:"invalid_candidate");
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void failed(Long id){jobs.findById(id).filter(j->Set.of("QUEUED","FAILED").contains(j.status()))
            .ifPresent(j->j.finish("FAILED","storage_failure"));}
    private static String text(JsonNode n,String key,int max){
        var value=n.get(key);
        if(value==null||!value.isTextual()||value.asText().isBlank()||value.asText().length()>max)throw new IllegalArgumentException();
        return value.asText();
    }
    private static String optional(JsonNode n,String key,int max){return n.hasNonNull(key)?text(n,key,max):null;}
}
