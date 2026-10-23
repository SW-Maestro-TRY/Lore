package com.lore.zzal.chat.presence;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name="zzal_chat_visit", uniqueConstraints=@UniqueConstraint(name="uk_zzal_chat_visit", columnNames={"pet_id","visit_id"}))
public class ZzalChatVisit {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(name="pet_id",nullable=false) private Long petId;
    @Column(name="visit_id",nullable=false) private UUID visitId;
    @Column(name="last_sequence",nullable=false) private long lastSequence;
    @Column(nullable=false) private boolean active;
    @Column(name="left_at") private Instant leftAt;
    protected ZzalChatVisit() { }
    public ZzalChatVisit(Long petId, UUID visitId) { this.petId=petId;this.visitId=visitId; }
    public boolean active() { return active; }
    public Instant leftAt() { return leftAt; }
    public long sequence() { return lastSequence; }
    public void enter(long seq) { active=true;lastSequence=seq; }
    public void leave(long seq, Instant now) {
        // duplicated visibility/pagehide cannot move the departure clock.
        if(active) leftAt=now;
        active=false;lastSequence=seq;
    }
}
