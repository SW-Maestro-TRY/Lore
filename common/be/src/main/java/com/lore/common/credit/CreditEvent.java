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
 * 크레딧이 움직인 한 번.
 *
 * <h2>잔액을 칸 하나로 안 들고 있는다</h2>
 *
 * 흔한 방법은 계정마다 잔액 칸 하나를 두고 더하고 빼는 것이다. 그런데 그러면
 * <b>"왜 지금 4인가" 에 답할 수가 없다.</b> 어긋나도(두 번 뺐다거나, 실패한
 * 생성에서 안 돌려줬다거나) 알아챌 방법이 없고, 알아채도 무엇을 되돌려야
 * 하는지 모른다. 돈에 준하는 값에서는 그게 제일 나쁘다.
 *
 * 그래서 <b>움직임만 쌓고 잔액은 그 합계</b>로 낸다. 지운 줄이 없으니 언제든
 * 처음부터 다시 세어 맞출 수 있고, 화면의 「내역」과 잔액이 같은 자료에서
 * 나오므로 둘이 어긋날 수가 없다.
 *
 * 합계를 매번 세는 것이 느려지면 그때 계정마다 요약 줄을 따로 둔다 — 지금
 * 규모(한 계정에 수십 줄)에서 미리 할 일이 아니고, 요약을 먼저 두면 그 요약이
 * 틀렸을 때 고칠 근거가 없어진다.
 *
 * <h2>같은 일을 두 번 적지 않는다</h2>
 *
 * {@code (user_id, reason, ref_id)} 가 유일하다. 만들기가 같은 작품으로 두 번
 * 차감을 시도해도 한 번만 빠진다 — 재시도·중복 클릭·네트워크 되풀이가 다
 * 여기서 걸린다. 부르는 쪽이 "이미 뺐던가" 를 기억할 필요가 없다.
 *
 * <h2>돌려줄 때도 지우지 않는다</h2>
 *
 * 환원은 뺀 줄을 지우는 것이 아니라 <b>반대 줄을 하나 더 적는 것</b>이다.
 * 지우면 "원래 얼마였나" 가 사라진다. 같은 {@code ref_id} 에 이유만 다르게
 * 적으므로, 한 번 낸 것을 두 번 돌려주는 일도 유일키가 막는다.
 */
@Entity
@Table(
        name = "credit_event",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_credit_event_once",
                columnNames = {"user_id", "reason", "ref_id"}),
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
     * 라면 이것은 "어디서인가" 다.
     *
     * <b>옛 줄은 비어 있다.</b> 이 칸이 생기기 전에 쌓인 것은 전부 웹툰에서
     * 나온 것이지만(그때는 웹툰만 크레딧을 썼다), 그렇다고 웹툰으로 채워
     * 넣지 않는다 — 실제로 그 줄에 적혀 있던 것은 "모른다" 이고, 나중에
     * 도메인별 합계를 낼 때 추측으로 채운 값이 실측처럼 보이면 안 된다.
     * 읽는 쪽은 비어 있으면 {@link CreditDomain#COMMON} 으로 본다.
     */
    @Column(length = 20)
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
