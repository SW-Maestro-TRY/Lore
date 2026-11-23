package com.lore.webtoon.gift;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 특정 사용자에게 띄우는 1회성 안내·보상 크레딧 한 건.
 *
 * 오류 사과 보상이나 개별 보상을 코드 수정 없이 관리자 API 로 등록하고,
 * 해당 계정이 로그인해 접속했을 때 팝업으로 안내한 뒤 1회만 지급한다.
 */
@Entity
@Table(name = "webtoon_user_gift")
public class UserGift {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ref_key", nullable = false, length = 120, unique = true)
    private String refKey;

    @Column(name = "email_key", nullable = false, length = 320)
    private String emailKey;

    @Column(nullable = false, length = 160)
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String message;

    @Column(nullable = false)
    private int credits;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "claimed_by")
    private Long claimedBy;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    protected UserGift() {
    }

    UserGift(String refKey, String emailKey, String title, String message,
             int credits, Long createdBy, Instant createdAt) {
        this.refKey = refKey;
        this.emailKey = emailKey;
        this.title = title;
        this.message = message;
        this.credits = credits;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    void markClaimed(Long userId, Instant at) {
        if (this.claimedAt == null) {
            this.claimedBy = userId;
            this.claimedAt = at;
        }
    }

    public Long getId() { return id; }
    public String getRefKey() { return refKey; }
    public String getEmailKey() { return emailKey; }
    public String getTitle() { return title; }
    public String getMessage() { return message; }
    public int getCredits() { return credits; }
    public Long getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Long getClaimedBy() { return claimedBy; }
    public Instant getClaimedAt() { return claimedAt; }
}
