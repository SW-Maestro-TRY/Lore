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
 * 서비스가 "자는 중" 을 함께 본다. BABY 는 <b>만료가 없다</b>({@code expiresAt} 이 비어 있다) —
 * 튜토리얼 부름이라 답할 때까지 기다린다(튜토리얼 중에는 시계가 멈춰 있다).
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

    // ── 대사를 누가 만들었나(채팅 v1, #704) ─────────────────────────────────
    // ★ 통계·폴백 추적용이다. 템플릿과 LLM 을 나란히 비교하고(v2 전환 판단), 폴백이 왜 났는지를
    //   행 단위로 본다. 한 행에 대사가 둘(부름·답)이라 생성기는 둘로, 모델·비용은 합쳐서 적는다.

    /** 부름 대사를 만든 생성기 — template · llm. 옛 행은 비어 있다(= template). */
    @Column(name = "line_generator", length = 10)
    private String lineGenerator;

    /** 답 대사를 만든 생성기 — template · llm. 답하기 전엔 비어 있다. */
    @Column(name = "reply_generator", length = 10)
    private String replyGenerator;

    /** LLM 을 불렀으면 그 모델(부름·답 중 마지막). */
    @Column(name = "model", length = 40)
    private String model;

    /** 이 행에서 나간 돈(USD) — 부름·답 LLM 호출의 합. 폴백이 나도 이미 나간 돈은 적는다. */
    @Column(name = "cost_usd", precision = 10, scale = 6)
    private java.math.BigDecimal costUsd;

    /** 폴백 사유 — "call:timeout", "reply:filter_unsafe" 처럼. 둘 다 났으면 ';' 로 잇는다. */
    @Column(name = "filtered_reason", length = 64)
    private String filteredReason;

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

    /** 부름 대사의 출처를 적는다. */
    public void noteLine(String generator, String model, java.math.BigDecimal cost, String fallbackReason) {
        this.lineGenerator = generator;
        note(model, cost, fallbackReason == null ? null : "call:" + fallbackReason);
    }

    /** 답 대사의 출처를 적는다. */
    public void noteReply(String generator, String model, java.math.BigDecimal cost, String fallbackReason) {
        this.replyGenerator = generator;
        note(model, cost, fallbackReason == null ? null : "reply:" + fallbackReason);
    }

    private void note(String model, java.math.BigDecimal cost, String reason) {
        if (model != null) {
            this.model = model;
        }
        if (cost != null && cost.signum() > 0) {
            this.costUsd = this.costUsd == null ? cost : this.costUsd.add(cost);
        }
        if (reason != null) {
            String joined = this.filteredReason == null ? reason : this.filteredReason + ";" + reason;
            this.filteredReason = joined.length() > 64 ? joined.substring(0, 64) : joined;
        }
    }

    public String getLineGenerator() {
        return lineGenerator;
    }

    public String getReplyGenerator() {
        return replyGenerator;
    }

    public String getModel() {
        return model;
    }

    public java.math.BigDecimal getCostUsd() {
        return costUsd;
    }

    public String getFilteredReason() {
        return filteredReason;
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
