package com.lore.zzal.chat.memory.facts;
import java.time.Instant;
public record FactView(Long id,String subject,String subjectRef,String content,String kind,String status,
        Long sourceTurnId,String sourceQuote,Instant observedAt,Long correctionOf,int revision,String operation) {
    public java.util.Map<String,Object> promptData(){
        var m=new java.util.LinkedHashMap<String,Object>();
        m.put("id",id);m.put("subject",subject);m.put("subject_ref",subjectRef);m.put("content",content);
        m.put("kind",kind);m.put("source_turn_id",sourceTurnId);m.put("source_quote",sourceQuote);
        m.put("observed_at",observedAt.toString());m.put("revision",revision);m.put("operation",operation);return m;
    }
    public static FactView from(ZzalFactMemory f){return new FactView(f.id(),f.subject().name(),f.subjectRef(),f.content(),
            f.kind().name(),f.status().name(),f.sourceTurnId(),f.sourceQuote(),f.observedAt(),f.correctionOf(),f.revision(),f.operation());}
}
