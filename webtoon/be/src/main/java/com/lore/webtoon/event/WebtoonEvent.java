package com.lore.webtoon.event;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 웹툰 화면에서 일어난 일 한 줄.
 *
 * 표를 만드는 SQL 에 왜 공용 행동 기록과 따로 두는지, 무엇을 안 담는지를 적었다
 * ({@code V20260926_0001__webtoon_event.sql}). 여기 들어오는 값은 전부
 * {@link EventService} 가 깎은 뒤다 — 이 클래스는 받은 것을 그대로 옮겨 담기만 한다.
 */
@Entity
@Table(
        name = "webtoon_event",
        indexes = {
                @Index(name = "idx_webtoon_event_name_time", columnList = "name, occurred_at"),
                @Index(name = "idx_webtoon_event_uid", columnList = "uid"),
                @Index(name = "idx_webtoon_event_user", columnList = "user_id"),
                @Index(name = "idx_webtoon_event_received", columnList = "received_at"),
        })
public class WebtoonEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 60)
    private String name;

    @Column(length = 64)
    private String uid;

    @Column(name = "user_id")
    private Long userId;

    @Column(length = 30)
    private String view;

    @Column(columnDefinition = "TEXT")
    private String props;

    @Column(length = 10)
    private String device;

    @Column(length = 100)
    private String source;

    @Column(name = "ref_host", length = 100)
    private String refHost;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    protected WebtoonEvent() {
    }

    static WebtoonEvent of(String name, String uid, Long userId, String view, String props,
                           String device, String source, String refHost,
                           Instant occurredAt, Instant receivedAt) {
        WebtoonEvent e = new WebtoonEvent();
        e.name = name;
        e.uid = uid;
        e.userId = userId;
        e.view = view;
        e.props = props;
        e.device = device;
        e.source = source;
        e.refHost = refHost;
        e.occurredAt = occurredAt;
        e.receivedAt = receivedAt;
        return e;
    }

    public String getName() {
        return name;
    }

    public String getUid() {
        return uid;
    }

    public Long getUserId() {
        return userId;
    }

    public String getView() {
        return view;
    }

    public String getProps() {
        return props;
    }

    public String getDevice() {
        return device;
    }

    public String getSource() {
        return source;
    }

    public String getRefHost() {
        return refHost;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
