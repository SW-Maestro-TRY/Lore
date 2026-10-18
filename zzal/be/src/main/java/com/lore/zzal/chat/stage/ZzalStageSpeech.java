package com.lore.zzal.chat.stage;
import jakarta.persistence.*;import java.time.Instant;import java.util.UUID;
import com.lore.zzal.chat.prompt.ConversationScene;
@Entity @Table(name="zzal_stage_speech",uniqueConstraints=@UniqueConstraint(name="uk_zzal_stage_speech_beat",columnNames={"pet_id","intro_version","step","phase"}))
public class ZzalStageSpeech {
 public enum Status { PENDING, READY, FAILED, UNCERTAIN, SUPERSEDED }
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @Column(name="owner_id",nullable=false) private Long ownerId;
 @Column(name="pet_id",nullable=false) private Long petId;
 @Column(name="intro_version",nullable=false) private int introVersion;
 @Enumerated(EnumType.STRING) @Column(nullable=false,length=24) private ConversationScene.Step step;
 @Enumerated(EnumType.STRING) @Column(nullable=false,length=24) private ConversationScene.Beat phase;
 @Enumerated(EnumType.STRING) @Column(nullable=false,length=24) private Status status;
 @Column(name="claim_token",nullable=false) private UUID claimToken;
 @Column(name="input_snapshot",nullable=false,columnDefinition="text") private String inputSnapshot;
 @Column(name="input_hash",nullable=false,length=64) private String inputHash;
 @Column(name="context_revision",nullable=false,length=64) private String contextRevision;
 @Column(name="expected_step",nullable=false,length=32) private String expectedStep;
 @Column(name="session_id",nullable=false) private Long sessionId;
 @Column(length=160) private String line;
 @Column(length=24) private String source;
 @Column(name="motion_id",length=40) private String motionId;
 @Column(name="turn_ref") private Long turnRef;
 @Column(name="cost_trace",columnDefinition="text") private String costTrace;
 @Column(name="failure_reason",length=120) private String failureReason;
 @Column(name="created_at",nullable=false) private Instant createdAt;
 @Column(name="updated_at",nullable=false) private Instant updatedAt;
 protected ZzalStageSpeech(){}
 public ZzalStageSpeech(Long owner,Long pet,int version,ConversationScene.Step step,ConversationScene.Beat phase,String expectedStep,Long session,String snapshot,String hash,String revision,Instant now){
  ownerId=owner;petId=pet;introVersion=version;this.step=step;this.phase=phase;this.expectedStep=expectedStep;sessionId=session;inputSnapshot=snapshot;inputHash=hash;contextRevision=revision;claimToken=UUID.randomUUID();status=Status.PENDING;createdAt=updatedAt=now;
 }
 public void complete(UUID token,Status state,String line,String source,String motion,Long turn,String cost,String reason,Instant now){
  if(status!=Status.PENDING||!claimToken.equals(token))return;status=state;this.line=line;this.source=source;motionId=motion;turnRef=turn;costTrace=cost;failureReason=reason;updatedAt=now;
 }
 @io.swagger.v3.oas.annotations.media.Schema(name="ZzalStageSpeechView")
 public record View(
 @io.swagger.v3.oas.annotations.media.Schema(requiredMode=io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED) Long id,
 @io.swagger.v3.oas.annotations.media.Schema(requiredMode=io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED) ConversationScene.Step step,
 @io.swagger.v3.oas.annotations.media.Schema(requiredMode=io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED) ConversationScene.Beat phase,
 @io.swagger.v3.oas.annotations.media.Schema(requiredMode=io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED) Status status,
 @io.swagger.v3.oas.annotations.media.Schema(requiredMode=io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED) String contextRevision,
 @io.swagger.v3.oas.annotations.media.Schema(nullable=true) String line,
 @io.swagger.v3.oas.annotations.media.Schema(nullable=true,description="LLM 또는 FALLBACK, PENDING/숨김은 null") String source,
 @io.swagger.v3.oas.annotations.media.Schema(nullable=true) String motionId,
 @io.swagger.v3.oas.annotations.media.Schema(nullable=true) Long turnRef,
 @io.swagger.v3.oas.annotations.media.Schema(requiredMode=io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED) Instant updatedAt){}

 public View view(boolean visible){boolean present=visible&&(status==Status.READY||"FALLBACK".equals(source));return new View(id,step,phase,visible?status:Status.SUPERSEDED,contextRevision,present?line:null,visible?source:null,present?motionId:null,present?turnRef:null,updatedAt);}
 public Long id(){return id;}public Long ownerId(){return ownerId;}public Long petId(){return petId;}public int introVersion(){return introVersion;}
 public ConversationScene.Step step(){return step;}public ConversationScene.Beat phase(){return phase;}public Status status(){return status;}public UUID token(){return claimToken;}
 public String snapshot(){return inputSnapshot;}public String hash(){return inputHash;}public String revision(){return contextRevision;}public String expectedStep(){return expectedStep;}public Long sessionId(){return sessionId;}
}
