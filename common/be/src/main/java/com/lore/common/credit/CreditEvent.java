package com.lore.common.credit;

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

import java.time.Instant;

/**
 */
@Entity
@Table(
        name = "credit_event",
        /* 「같은 일인가」의 기준. 이 넷이 같으면 한 번만 적힌다.
           **domain 이 여기 있어야 한다.** refId 는 서비스마다 자기 방식으로
           짓는 값이라(작품 id · 결제 id …) 웹툰의 것과 짤의 것이 우연히 같을
           수 있다. 도메인이 빠져 있으면 그때 뒤엣것이 "이미 적힌 일" 로 밀려
           조용히 사라진다 — 낸 사람은 냈는데 장부에 없는 상태다. */
        uniqueConstraints = @UniqueConstraint(
                name = "uk_credit_event_once",
                columnNames = {"user_id", "reason", "domain", "ref_id"}),
        indexes = @Index(name = "idx_credit_event_user", columnList = "user_id, id"))
public class CreditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /**
     * 얼마나 움직였나. 받으면 양수, 쓰면 음수다.
     *
     * 「+12 / -12」 로 적지 「12 를 어느 방향으로」 로 적지 않는다 — 방향을
     * 따로 들면 합계를 낼 때마다 방향을 해석해야 하고, 그 해석이 한 군데라도
     * 틀리면 잔액이 조용히 어긋난다. 그냥 더하면 되는 모양이 안전하다.
     */
    @Column(nullable = false)
    private int delta;

    @Column(nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    private CreditReason reason;

    /**
     * 어느 서비스에서 일어난 일인가. 이유(reason)가 "무슨 성격의 움직임인가"
     */
    /* **안 비운다.** 유일키에 들어가는 칸인데, 포스트그레스는 유일키에서
       NULL 을 서로 다른 값으로 친다 — 비어 있으면 같은 일이 몇 번이고 다시
       적힌다. 아래 refId 에 적힌 것과 같은 이유다. */
    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private CreditDomain domain;

    /**
     * 무엇 때문인가 — 작품 id, 결제 id 같은 것.
     *
     * 이유별로 하나뿐인 일(가입 축하 등)에는 이유 이름을 그대로 넣는다.
     * {@code null} 을 안 쓰는 이유: 유일키에서 {@code null} 은 서로 다른
     * 값으로 쳐서, 비워 두면 같은 일이 몇 번이고 다시 적힌다.
     */
    @Column(name = "ref_id", nullable = false, length = 100)
    private String refId;

    /** 사람이 읽을 한 줄. 화면의 「내역」에 그대로 나간다. */
    @Column(length = 200)
    private String memo;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected CreditEvent() {
    }

    private CreditEvent(Long userId, int delta, CreditReason reason, CreditDomain domain,
                        String refId, String memo, Instant createdAt) {
        this.userId = userId;
        this.delta = delta;
        this.reason = reason;
        this.domain = domain == null ? CreditDomain.COMMON : domain;
        this.refId = refId;
        this.memo = memo;
        this.createdAt = createdAt;
    }

    static CreditEvent of(Long userId, int delta, CreditReason reason, CreditDomain domain,
                          String refId, String memo, Instant at) {
        return new CreditEvent(userId, delta, reason, domain, refId, memo, at);
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public int getDelta() {
        return delta;
    }

    public CreditReason getReason() {
        return reason;
    }

    /** 어디서 일어난 일인가. 이 칸이 생기기 전 줄은 {@code COMMON} 으로 읽는다. */
    public CreditDomain getDomain() {
        return domain == null ? CreditDomain.COMMON : domain;
    }

    public String getRefId() {
        return refId;
    }

    public String getMemo() {
        return memo;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
