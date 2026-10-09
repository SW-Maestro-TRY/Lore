package com.lore.zzal.chat.session;

import com.lore.zzal.chat.ChatSlot;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 대화 한 판 — 부름 하나에서 시작해 펫 턴과 사용자 턴이 오가다 닫힌다(#704).
 *
 * <h3>★ 옛 {@code zzal_chat_call} 과의 관계</h3>
 * 슬롯·날·만료 규칙은 옛 부름과 같다((펫, 날, 슬롯) 유니크, BABY 는 만료 없음). 다른 점은 답이 한 번이 아니라
 * 최대 {@code app.zzal.chat.max-rounds} 왕복이라는 것. 보상(+40·답 카운터·튜토리얼 넘김)은 <b>세션당 1회</b>,
 * 첫 답에서 준다({@link #rewardGiven}).
 *
 * <h3>닫히는 길</h3>
 * {@link CloseReason} — 상한에 닿아 닫기 턴(CLOSED) · 시각이 지남(EXPIRED) · 답하다 말았음(ABANDONED).
 * 만료·이탈은 타이머가 아니라 <b>읽을 때</b> 판정해 적는다(부름을 물어볼 때 만드는 것과 같은 이유).
 */
@Entity
@Table(name = "zzal_chat_session",
        uniqueConstraints = @UniqueConstraint(name = "uk_zzal_chat_session_day_slot", columnNames = {"pet_id", "day_of", "slot"}),
        indexes = @Index(name = "idx_zzal_chat_session_pet", columnList = "pet_id"))
public class ZzalChatSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pet_id", nullable = false)
    private Long petId;

    @Column(name = "day_of", nullable = false)
    private LocalDate dayOf;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ChatSlot slot;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SessionKind kind;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "close_reason", length = 10)
    private CloseReason closeReason;

    @Column(name = "round_count", nullable = false)
    private int roundCount;

    /** 이번 판에서 사용자가 마지막으로 한 말 — 다음 판 지시문의 "지난 대화 마지막 말". */
    @Column(name = "last_user_line", length = 40)
    private String lastUserLine;

    @Column(name = "reward_given", nullable = false)
    private boolean rewardGiven;

    /** 펫 턴을 만든 생성기 집계 — template · llm · mixed. */
    @Column(length = 10)
    private String generator;

    /** 이 판에서 LLM 에 나간 돈의 합(폴백이어도 이미 나간 돈은 센다). */
    @Column(name = "cost_usd", precision = 10, scale = 6)
    private BigDecimal costUsd;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ZzalChatSession() {
    }

    public static ZzalChatSession open(Long petId, LocalDate dayOf, ChatSlot slot, SessionKind kind,
                                       Instant startedAt, Instant expiresAt, Instant now) {
        ZzalChatSession s = new ZzalChatSession();
        s.petId = petId;
        s.dayOf = dayOf;
        s.slot = slot;
        s.kind = kind;
        s.startedAt = startedAt;
        s.expiresAt = expiresAt;
        s.createdAt = now;
        return s;
    }

    /** 답할 수 있나 — 안 닫혔고 시각이 안 지났다. */
    public boolean isOpen(Instant now) {
        return closeReason == null && !isPastExpiry(now);
    }

    public boolean isPastExpiry(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }

    public boolean isClosed() {
        return closeReason != null;
    }

    /** 사용자 턴 하나를 센다. 첫 답이면 true(보상을 줄 차례). */
    public boolean countUserTurn(String line) {
        roundCount += 1;
        lastUserLine = line == null ? null : (line.length() > 40 ? line.substring(0, 40) : line);
        if (rewardGiven) {
            return false;
        }
        rewardGiven = true;
        return true;
    }

    /** 펫 턴의 출처를 집계한다. */
    public void notePetTurn(String gen, BigDecimal cost) {
        if (gen != null) {
            generator = generator == null || generator.equals(gen) ? gen : "mixed";
        }
        if (cost != null && cost.signum() > 0) {
            costUsd = costUsd == null ? cost : costUsd.add(cost);
        }
    }

    public void close(CloseReason reason, Instant at) {
        if (closeReason == null) {
            closeReason = reason;
            closedAt = at;
        }
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

    public SessionKind getKind() {
        return kind;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public CloseReason getCloseReason() {
        return closeReason;
    }

    public int getRoundCount() {
        return roundCount;
    }

    public String getLastUserLine() {
        return lastUserLine;
    }

    public boolean isRewardGiven() {
        return rewardGiven;
    }

    public String getGenerator() {
        return generator;
    }

    public BigDecimal getCostUsd() {
        return costUsd;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
