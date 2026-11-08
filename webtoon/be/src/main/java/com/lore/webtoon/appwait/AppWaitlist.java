package com.lore.webtoon.appwait;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * LORE 앱 출시 알림 신청 한 줄(#981). 한 줄이 한 이메일이다 — 같은 이메일은 {@code emailKey} 로 겹침을 막는다.
 * 출시날 50크레딧을 주면 {@code grantedAt} 을 채운다.
 */
@Entity
@Table(name = "webtoon_app_waitlist")
public class AppWaitlist {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 320)
    private String email;

    @Column(name = "email_key", nullable = false, length = 320, unique = true)
    private String emailKey;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "browser_uid", length = 64)
    private String browserUid;

    @Column(length = 16)
    private String device;

    @Column(length = 40)
    private String source;

    @Column(nullable = false, length = 8)
    private String lang;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "granted_at")
    private Instant grantedAt;

    protected AppWaitlist() {
    }

    AppWaitlist(String email, String emailKey, Long userId, String browserUid, String device, String source, String lang, Instant now) {
        this.email = email;
        this.emailKey = emailKey;
        this.userId = userId;
        this.browserUid = browserUid;
        this.device = device;
        this.source = source;
        this.lang = lang;
        this.createdAt = now;
    }

    /** 처음엔 로그인 없이 신청했다가 나중에 로그인한 채로 다시 누르면 계정을 이어 적는다. */
    void attachUser(Long userId) {
        if (this.userId == null && userId != null) {
            this.userId = userId;
        }
    }

    public Long getId() { return id; }
    public String getEmail() { return email; }
    public Long getUserId() { return userId; }
    public Instant getCreatedAt() { return createdAt; }
}
