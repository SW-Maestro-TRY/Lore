package com.lore.zzal.chat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 캐릭터가 먼저 건 부름 하나(정본 10장). 답이 오면 그 행에 답과 대사가 남는다.
 *
 * <h3>★ 왜 행으로 남기나</h3>
 * "오늘 아침 부름에 답했나" 는 카운터로 못 센다(슬롯마다 한 번). 그리고 답 5개를 기억해 재언급하려면
 * 답 자체가 남아야 한다(10장 "기억"). (펫, 날, 슬롯) 유니크로 같은 부름이 두 번 생기지 않는다.
 *
 * <h3>만료</h3>
 * {@code expiresAt} 이 지나면 닫힌다. EVENING 은 잠들 때 닫히므로 expiresAt 은 23:00(자동 취침 상한)이고
 * 서비스가 "자는 중" 을 함께 본다. BABY 는 60분 뒤 첫 밤 경계.
 */
@Entity
@Table(name = "zzal_chat_call",
        uniqueConstraints = @UniqueConstraint(name = "uk_zzal_chat_call_day_slot", columnNames = {"pet_id", "day_of", "slot"}),
        indexes = @Index(name = "idx_zzal_chat_call_pet", columnList = "pet_id"))
@EntityListeners(AuditingEntityListener.class)
public class ZzalChatCall {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pet_id", nullable = false)
    private Long petId;

    /** 어느 날의 부름인가 — 기상 시각의 KST 날짜(BABY 는 부화 날짜). 하루 경계가 잠드는 순간이라 날짜만으론 안 되지만, 기상일로 묶으면 하루에 슬롯 하나다. */
    @Column(name = "day_of", nullable = false)
    private LocalDate dayOf;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ChatSlot slot;

    /** 캐릭터가 먼저 한 줄. */
    @Column(nullable = false, length = 120)
    private String line;

    @Column(nullable = false)
    private Instant calledAt;

    @Column
    private Instant expiresAt;

    @Column
    private Instant answeredAt;

    /** 사용자의 답(40자). 기억 재료. */
    @Column(length = 40)
    private String answer;

    /** 답에 대한 캐릭터 대사(원망 필터를 지난 것). */
    @Column(length = 160)
    private String replyLine;

    /** 반응 동작 key. */
    @Column(length = 20)
    private String reactionKey;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected ZzalChatCall() {
    }

    public static ZzalChatCall call(Long petId, LocalDate dayOf, ChatSlot slot, String line, Instant calledAt, Instant expiresAt) {
        ZzalChatCall c = new ZzalChatCall();
        c.petId = petId;
        c.dayOf = dayOf;
        c.slot = slot;
        c.line = line;
        c.calledAt = calledAt;
        c.expiresAt = expiresAt;
        return c;
    }

    public void answer(String answer, String replyLine, String reactionKey, Instant now) {
        this.answer = answer;
        this.replyLine = replyLine;
        this.reactionKey = reactionKey;
        this.answeredAt = now;
    }

    public boolean isAnswered() {
        return answeredAt != null;
    }

    public boolean isExpired(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }

    /** 답할 수 있나 — 안 답했고 안 만료됐고. */
    public boolean isOpen(Instant now) {
        return !isAnswered() && !isExpired(now);
    }

    public Long getId() {
        return id;
    }

    public Long getPetId() {
        return petId;
    }

    public LocalDate getDayOf() {
        return dayOf;
    }

    public ChatSlot getSlot() {
        return slot;
    }

    public String getLine() {
        return line;
    }

    public Instant getCalledAt() {
        return calledAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getAnsweredAt() {
        return answeredAt;
    }

    public String getAnswer() {
        return answer;
    }

    public String getReplyLine() {
        return replyLine;
    }

    public String getReactionKey() {
        return reactionKey;
    }
}
