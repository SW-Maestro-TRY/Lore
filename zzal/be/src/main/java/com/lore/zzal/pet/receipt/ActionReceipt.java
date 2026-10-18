package com.lore.zzal.pet.receipt;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** An execution fact, never a cached current pet snapshot. */
@Entity @Table(name="zzal_action_receipt",uniqueConstraints=@UniqueConstraint(name="uq_zzal_action_receipt_pet_request",columnNames={"pet_id","request_id"}))
public class ActionReceipt {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @Column(name="pet_id",nullable=false) private Long petId;
 @Column(name="request_id",nullable=false) private UUID requestId;
 @Column(name="action_kind",nullable=false,length=32) private String actionKind;
 @Column(name="request_hash",nullable=false,length=64) private String requestHash;
 @Column(name="response_version",nullable=false) private int responseVersion;
 @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
 @Column(name="result_json",nullable=false,columnDefinition="jsonb") private String resultJson;
 @Column(name="completed_at",nullable=false) private Instant completedAt;
 protected ActionReceipt() {}
 public ActionReceipt(Long petId,UUID requestId,String hash,String result,Instant at){this(petId,requestId,"CARE_PET",hash,result,at);}
 public ActionReceipt(Long petId,UUID requestId,String kind,String hash,String result,Instant at){this.petId=petId;this.requestId=requestId;this.actionKind=kind;this.requestHash=hash;this.responseVersion=1;this.resultJson=result;this.completedAt=at;}
 public boolean matches(String hash){return matches(hash,"CARE_PET");}
 public boolean matches(String hash,String kind){return responseVersion==1 && kind.equals(actionKind)&&requestHash.equals(hash);}
 public String resultJson(){return resultJson;}
}
