package com.lore.webtoon.push;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 이 기기(브라우저)로 웹툰 알림을 받겠다는 기록. 한 줄이 한 기기다.
 *
 * 로그인한 사람의 작업은 {@code userId} 로, 게스트의 작업은 {@code browserUid}
 * ({@code webtoon_job.browser_uid} 와 같은 값)로 찾는다 — {@link JobPush#recipientsOf}.
 */
@Entity
@Table(name = "webtoon_push_subscription")
public class PushSubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 1024, unique = true)
    private String endpoint;

    @Column(nullable = false, length = 128)
    private String p256dh;

    @Column(nullable = false, length = 64)
    private String auth;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "browser_uid", length = 64)
    private String browserUid;

    @Column(nullable = false, length = 8)
    private String lang;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "used_at", nullable = false)
    private Instant usedAt;

    protected PushSubscription() {
    }

    PushSubscription(String endpoint, Instant now) {
        this.endpoint = endpoint;
        this.createdAt = now;
        this.usedAt = now;
    }

    /**
     * 브라우저가 준 키와 지금 누구인지를 적는다.
     *
     * 같은 기기가 다시 구독하면 이 줄을 고쳐 쓴다. 로그인 안 한 채로 다시 오면
     * {@code userId} 를 지운다 — 남겨 두면 로그아웃한 기기로 그 계정의 작업
     * 알림이 계속 간다.
     */
    void update(String p256dh, String auth, Long userId, String browserUid, String lang, Instant now) {
        this.p256dh = p256dh;
        this.auth = auth;
        this.userId = userId;
        this.browserUid = browserUid;
        this.lang = lang;
        this.usedAt = now;
    }

    public Long getId() {
        return id;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public String getP256dh() {
        return p256dh;
    }

    public String getAuth() {
        return auth;
    }

    public Long getUserId() {
        return userId;
    }

    public String getBrowserUid() {
        return browserUid;
    }

    public String getLang() {
        return lang;
    }

    public Instant getUsedAt() {
        return usedAt;
    }
}
