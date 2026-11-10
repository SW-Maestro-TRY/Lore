package com.lore.webtoon.lorebook;

import jakarta.persistence.*;
import java.time.Instant;

@Entity @Table(name="webtoon_private_lorebook")
public class PrivateLorebook {
 @Id @Column(length=40) String id;
 @Column(nullable=false) Long ownerId;
 @Column(nullable=false) Instant createdAt;
 @Column(nullable=false) Instant updatedAt;
 @Column(nullable=false,columnDefinition="text") String bookJson;
 protected PrivateLorebook() {}
 PrivateLorebook(String id,Long owner,String json) {
  this.id=id;ownerId=owner;bookJson=json;createdAt=updatedAt=Instant.now();
 }
}
