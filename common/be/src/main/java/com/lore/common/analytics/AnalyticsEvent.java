package com.lore.common.analytics;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 사용자가 무엇을 했는지 한 줄.
 *
 * <h3>왜 필요한가</h3>
 * 지인 10명 규모에서는 숫자가 아니라 <b>각자가 어디서 멈췄는지</b>가 유일한 정보다.
 * 화면에는 이미 41곳에 기록 지점이 심어져 있고, 받아 줄 곳이 없었을 뿐이다.
 *
 * <h3>★ 개인정보가 새지 않게 하는 것이 이 표의 설계다</h3>
 * 화면 쪽 코드는 지금 조심스럽게 짜여 있지만(에러는 코드만, 이름은 "쳤다"만),
 * <b>그 조심스러움이 코드로 강제돼 있지 않다.</b> 앞으로 한 줄만 잘못 들어가면 샌다.
 * 그래서 서버에서 막는다 — 아래 칸의 생김새 자체가 방어다.
 *
 *   · {@code props} 는 자유 JSON 이 아니라 <b>허용된 키만</b> 통과한다(수집기가 거른다)
 *   · {@code referrer} 는 <b>쿼리스트링을 버린</b> origin + path 만. 쿼리에는 이메일·토큰이 실려 온다
 *   · User-Agent 원문과 IP 는 <b>아예 칸이 없다</b>. 그대로 저장하면 지문이 된다
 *
 * <h3>익명과 로그인을 잇는 법</h3>
 * 두 칸을 <b>같이</b> 둔다. 로그인 상태면 서버가 {@code userId} 를 채운다.
 * 지난 이벤트를 소급해서 고치지 않는다 — {@link AnonIdentity} 한 줄로 나중에 이어 볼 수 있고,
 * 대량 UPDATE 는 작은 서버에서 위험하다.
 */
@Entity
@Table(
        name = "zzal_event",
        indexes = {
                @Index(name = "idx_event_anon", columnList = "anon_id"),
                @Index(name = "idx_event_user", columnList = "user_id"),
                @Index(name = "idx_event_name_time", columnList = "name, occurred_at")
        })
public class AnalyticsEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 무엇을 했나. 화면이 부르는 이름 그대로(예: zzal_hatch_abandoned). */
    @Column(nullable = false, length = 60)
    private String name;

    /** 브라우저마다 하나. 로그인 전에도 이어서 볼 수 있게 하는 열쇠. */
    @Column(name = "anon_id", nullable = false, length = 40)
    private String anonId;

    /** 로그인한 사람이면 누구인지. 비로그인이면 비어 있다. */
    @Column(name = "user_id")
    private Long userId;

    /**
     * 곁들이는 값 몇 개. 허용된 키만, 짧은 값만.
     *
     * ★ 자유 JSON 으로 두지 않는 이유 — 언젠가 누군가 이메일이나 입력한 글을 통째로 넣는다.
     *   수집기가 화이트리스트로 거른 뒤 여기에 담는다.
     */
    @Column(columnDefinition = "text")
    private String props;

    /** 어느 화면에서 일어났나. 경로만(쿼리 제외). */
    @Column(length = 200)
    private String path;

    /** 어디서 들어왔나. origin + path 만. */
    @Column(length = 200)
    private String referrer;

    /** 유입 출처(utm_source 등)를 접어 담는다. */
    @Column(length = 100)
    private String source;

    /** 기기 대분류(mobile/desktop). 원문 User-Agent 는 저장하지 않는다. */
    @Column(length = 20)
    private String device;

    /**
     * 실험 집단.
     *
     * ★ 지금은 나눌 설정이 없어 항상 "default" 다. 그래도 칸은 지금 만든다 —
     *   나중에 스키마를 바꾸는 것보다 지금 비워 두는 편이 싸다.
     */
    @Column(length = 30)
    private String variant;

    /** 화면에서 일어난 시각(브라우저 시계). 서버 도착 시각과 다를 수 있다. */
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    protected AnalyticsEvent() {
    }

    public static AnalyticsEvent of(String name, String anonId, Long userId, String props,
                                    String path, String referrer, String source, String device,
                                    String variant, Instant occurredAt, Instant receivedAt) {
        AnalyticsEvent e = new AnalyticsEvent();
        e.name = name;
        e.anonId = anonId;
        e.userId = userId;
        e.props = props;
        e.path = path;
        e.referrer = referrer;
        e.source = source;
        e.device = device;
        e.variant = variant == null ? "default" : variant;
        e.occurredAt = occurredAt;
        e.receivedAt = receivedAt;
        return e;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getAnonId() {
        return anonId;
    }

    public Long getUserId() {
        return userId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
