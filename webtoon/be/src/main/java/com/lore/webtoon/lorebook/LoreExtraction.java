package com.lore.webtoon.lorebook;
import jakarta.persistence.*;
import java.time.Instant;
@Entity @Table(name="webtoon_lore_extraction")
public class LoreExtraction {
 @Id @Column(length=40) String id;
 @Column(nullable=false) Long ownerId;
 @Column(nullable=false) Instant createdAt;
 @Column(nullable=false,length=20) String status="RUNNING";
 @Column(columnDefinition="text") String resultJson;
 @Column(length=300) String error;
 protected LoreExtraction() {}
 LoreExtraction(String id,Long owner) {this.id=id;ownerId=owner;createdAt=Instant.now();}
}
