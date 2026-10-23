package com.lore.zzal.chat.delivery;

import com.lore.zzal.chat.ChatSlot;
import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;

/** 대화의 길이와 분리한 기존 보상 창(튜토리얼1회/일상 창당1회). */
@Entity
@Table(name="zzal_chat_reward",uniqueConstraints=@UniqueConstraint(name="uk_zzal_chat_reward_window",
        columnNames={"pet_id","day_of","slot"}))
public class ZzalChatReward {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(name="pet_id",nullable=false) private Long petId;
    @Column(name="day_of",nullable=false) private LocalDate dayOf;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=10) private ChatSlot slot;
    @Column(name="created_at",nullable=false) private Instant createdAt;
    protected ZzalChatReward() { }
    public ZzalChatReward(Long petId,LocalDate day,ChatSlot slot,Instant now) {
        this.petId=petId;this.dayOf=day;this.slot=slot;this.createdAt=now;
    }
}
