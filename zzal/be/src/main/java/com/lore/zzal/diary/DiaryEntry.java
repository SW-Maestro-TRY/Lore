package com.lore.zzal.diary;

import jakarta.persistence.*;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Stored fictional character writing. Never a chat USER turn or fact-capture input. */
@Entity
@Table(name="zzal_diary_entry")
public class DiaryEntry {
    public enum Status { RESERVED, GENERATING, READY, FAILED, UNCERTAIN }
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(nullable=false) private Long petId;
    @Column(nullable=false,unique=true,length=120) private String opportunityId;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=20) private Status status;
    @Column(nullable=false) private Instant createdAt;
    @Column(nullable=false) private Instant writtenAt;
    private Instant readyAt;
    private Instant readAt;
    @Column(columnDefinition="text") private String body;
    @Column(length=300) private String preview;
    @Column(nullable=false,length=12) private String language;
    @Column(nullable=false,columnDefinition="text") private String sourceSnapshotJson;
    @Column(nullable=false,length=64) private String sourceHash;
    @Column(length=64) private String bodyHash;
    @Column(nullable=false,length=40) private String sourceType="DIARY_FICTION";
    @Column(nullable=false,length=40) private String promptVersion="diary-v1";
    @Column(length=80) private String claimToken;
    private Instant claimedAt;
    @Column(columnDefinition="text") private String executionReceipt;
    protected DiaryEntry() {}
    public static DiaryEntry reserve(Long petId,String opportunity,Instant writtenAt,Instant now,String language,String snapshot){
        var e=new DiaryEntry();e.petId=petId;e.opportunityId=opportunity;e.writtenAt=writtenAt;
        e.createdAt=now;e.language=language;e.sourceSnapshotJson=snapshot;e.sourceHash=hash(snapshot);e.status=Status.RESERVED;return e;
    }
    public boolean claim(String token,Instant now){
        if(status!=Status.RESERVED)return false;
        status=Status.GENERATING;claimToken=token;claimedAt=now;return true;
    }
    public boolean complete(String token,String content,String receipt,Instant now){
        if(status!=Status.GENERATING||!java.util.Objects.equals(token,claimToken))return false;
        executionReceipt=receipt;
        if(content==null||content.isBlank()||structuredContent(content)){status=Status.FAILED;return false;}
        body=content;preview=content.codePoints().limit(120).collect(StringBuilder::new,StringBuilder::appendCodePoint,StringBuilder::append).toString();
        bodyHash=hash(content);readyAt=now;status=Status.READY;return true;
    }
    private static boolean structuredContent(String content){
        try{var node=new com.fasterxml.jackson.databind.ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(content);return node!=null&&(node.isObject()||node.isArray());}
        catch(Exception prose){return false;}
    }
    public void uncertain(String token,String receipt){
        if(status==Status.GENERATING&&java.util.Objects.equals(token,claimToken)){status=Status.UNCERTAIN;executionReceipt=receipt;}
    }
    public void read(Instant now){if(status!=Status.READY)throw new IllegalStateException("Not ready");if(readAt==null)readAt=now.truncatedTo(java.time.temporal.ChronoUnit.MICROS);}
    public boolean occupiesSlot(){return readAt==null;}
    public static String hash(String s){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    public Long id(){return id;} public Long petId(){return petId;} public Status status(){return status;}
    public Instant writtenAt(){return writtenAt;} public Instant readAt(){return readAt;}
    public String body(){return body;} public String preview(){return preview;} public String language(){return language;}
    public String snapshot(){return sourceSnapshotJson;} public String bodyHash(){return bodyHash;}
    public String claimToken(){return claimToken;} public Instant claimedAt(){return claimedAt;}
}
