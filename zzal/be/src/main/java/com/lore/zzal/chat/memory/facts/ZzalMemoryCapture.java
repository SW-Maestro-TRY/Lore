package com.lore.zzal.chat.memory.facts;
import jakarta.persistence.*;
import java.time.Instant;
@Entity
@Table(name="zzal_memory_capture",uniqueConstraints=@UniqueConstraint(name="uk_zzal_memory_capture_source",columnNames={"user_turn_id","extractor_version"}))
public class ZzalMemoryCapture {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(name="pet_id",nullable=false) private Long petId;
    @Column(name="user_turn_id",nullable=false) private Long userTurnId;
    @Column(name="pet_turn_id",nullable=false) private Long petTurnId;
    @Column(name="extractor_version",nullable=false,length=32) private String extractorVersion;
    @Column(name="raw_deltas",nullable=false,columnDefinition="text") private String rawDeltas;
    @Column(name="recalled_ids",nullable=false,columnDefinition="text") private String recalledIds;
    @Column(nullable=false,length=16) private String status;
    @Column(name="failure_reason",length=100) private String failureReason;
    @Column(name="created_at",nullable=false) private Instant createdAt;
    protected ZzalMemoryCapture() { }
    public ZzalMemoryCapture(Long pet,Long userTurn,Long petTurn,String version,String raw,String recalled,Instant now){
        petId=pet;userTurnId=userTurn;petTurnId=petTurn;extractorVersion=version;rawDeltas=raw;recalledIds=recalled;
        createdAt=now;status="QUEUED";
    }
    public Long id(){return id;}public Long petId(){return petId;}public Long userTurnId(){return userTurnId;}
    public Long petTurnId(){return petTurnId;}public String rawDeltas(){return rawDeltas;}public String recalledIds(){return recalledIds;}
    public String status(){return status;}public Instant at(){return createdAt;}public String version(){return extractorVersion;}
    public void finish(String status,String reason){this.status=status;failureReason=reason;}
}
