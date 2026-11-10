package com.lore.zzal.chat.memory.facts;
import jakarta.persistence.*;
import java.time.Instant;
@Entity
@Table(name="zzal_fact_memory",indexes=@Index(name="idx_zzal_fact_memory_pet_status",columnList="pet_id,status,id"))
public class ZzalFactMemory {
    public enum Subject { USER, CHARACTER, THIRD_PARTY, UNKNOWN }
    public enum Kind { USER_REPORTED, INFERENCE, FICTION }
    public enum Status { PENDING, ACTIVE, SUPERSEDED, RETRACTED }
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(name="owner_id",nullable=false) private Long ownerId;
    @Column(name="pet_id",nullable=false) private Long petId;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=16) private Subject subject;
    @Column(name="subject_ref",length=100) private String subjectRef;
    @Column(nullable=false,length=1000) private String content;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=16) private Kind kind;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=16) private Status status;
    @Column(name="source_turn_id",nullable=false) private Long sourceTurnId;
    @Column(name="source_quote",nullable=false,length=1000) private String sourceQuote;
    @Column(name="observed_at",nullable=false) private Instant observedAt;
    @Column(name="correction_of") private Long correctionOf;
    @Column(nullable=false) private int revision;
    @Column(nullable=false,length=12) private String operation="ADD";
    public void operation(String value){operation=value;}
    public String operation(){return operation;}
    @Column(name="extractor_version",nullable=false,length=32) private String extractorVersion;
    protected ZzalFactMemory() { }
    public ZzalFactMemory(Long owner,Long pet,Subject subject,String ref,String content,Kind kind,Status status,
            Long source,String quote,Instant at,Long correctionOf,int revision,String extractorVersion) {
        ownerId=owner;petId=pet;this.subject=subject;subjectRef=ref;this.content=content;this.kind=kind;this.status=status;
        sourceTurnId=source;sourceQuote=quote;observedAt=at;this.correctionOf=correctionOf;this.revision=revision;
        this.extractorVersion=extractorVersion;
    }
    public Long id(){return id;} public Long ownerId(){return ownerId;} public Long petId(){return petId;}
    public Subject subject(){return subject;} public String subjectRef(){return subjectRef;} public String content(){return content;}
    public Kind kind(){return kind;} public Status status(){return status;} public Long sourceTurnId(){return sourceTurnId;}
    public String sourceQuote(){return sourceQuote;} public Instant observedAt(){return observedAt;}
    public Long correctionOf(){return correctionOf;} public int revision(){return revision;}
    public void supersede(){status=Status.SUPERSEDED;} public void retract(){status=Status.RETRACTED;}
}
