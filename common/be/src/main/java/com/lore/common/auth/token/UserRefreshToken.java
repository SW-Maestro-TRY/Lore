package com.lore.common.auth.token;

import com.lore.common.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * refresh 토큰. access 토큰을 새로 받아오는 용도로만 쓰인다.
 *
 * ★ 이 표가 JWT 의 약점을 메운다.
 *   JWT 는 서버가 아무것도 기억하지 않는 것이 장점인데, 그래서 **한 번 발급하면 취소가 안 된다**.
 *   로그아웃을 눌러도, 탈퇴해도, 토큰이 유출돼도 만료까지는 유효하다.
 *   그래서 오래 사는 쪽(refresh)만 DB 에 두고 revokedAt 으로 취소할 수 있게 한다.
 *   access 는 30분이라 그 사이만 버티면 되고, refresh 가 죽으면 새 access 를 못 받아 끊긴다.
 *
 * ★ 원문이 아니라 해시를 저장한다. DB 가 유출되면 그 토큰들로 전부 로그인할 수 있게 되므로
 *   비밀번호와 같은 급으로 다룬다.
 *
 * ★ 회전(rotation) — 갱신할 때마다 새로 발급하고 옛 것을 폐기한다. 탈취돼도 한 번만 쓰인다.
 *   이미 폐기된 토큰이 다시 들어오면 탈취 신호로 보고 그 사용자의 토큰을 전부 폐기한다.
 *
 * ★ 절대 만료(absoluteExpiresAt) — 회전은 쓸 때마다 expiresAt 을 14일 뒤로 미룬다. 그래서
 *   매일 쓰는 사용자는 영원히 재로그인하지 않는다. 그 위에 <b>최초 로그인 시점부터의 상한</b>을
 *   하나 더 둔다. 회전할 때 이 값은 <b>물려받기만</b> 하고 다시 늘리지 않으므로, 상한이 지나면
 *   expiresAt 을 아무리 밀어도 이 선을 넘지 못해 isUsable 이 자연히 false 가 되고 재로그인으로 간다.
 *   비어 있으면(이 기능 이전에 발급된 토큰) 상한 없음으로 관대하게 다룬다.
 */
@Entity
@Table(
        name = "user_refresh_token",
        uniqueConstraints = @UniqueConstraint(name = "uk_refresh_token_hash", columnNames = "token_hash"),
        indexes = @Index(name = "idx_refresh_user", columnList = "user_id"))
public class UserRefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_refresh_user"))
    private User user;

    @Column(nullable = false, length = 100)
    private String tokenHash;

    @Column(nullable = false)
    private Instant expiresAt;

    /**
     * 최초 로그인 시점에 찍는 <b>절대 상한</b>. 회전해도 물려받기만 하고 늘리지 않는다.
     * 비어 있으면(이 기능 이전 토큰) 상한이 없는 것으로 본다.
     */
    @Column
    private Instant absoluteExpiresAt;

    /** 로그아웃·회전·탈취 감지로 폐기된 시각. 비어 있으면 살아 있다. */
    @Column
    private Instant revokedAt;

    /** 어느 기기에서 발급됐는지. 나중에 "다른 기기 로그아웃" 화면을 만들 때 쓴다. */
    @Column(length = 300)
    private String userAgent;

    @Column(nullable = false)
    private Instant createdAt;

    protected UserRefreshToken() {
    }

    public static UserRefreshToken issue(User user, String tokenHash, Instant expiresAt,
                                         Instant absoluteExpiresAt, String userAgent, Instant now) {
        UserRefreshToken t = new UserRefreshToken();
        t.user = user;
        t.tokenHash = tokenHash;
        t.expiresAt = expiresAt;
        t.absoluteExpiresAt = absoluteExpiresAt;
        t.userAgent = userAgent;
        t.createdAt = now;
        return t;
    }

    public void revoke(Instant now) {
        if (this.revokedAt == null) {
            this.revokedAt = now;
        }
    }

    /**
     * 살아 있고 아직 안 지난 토큰인가.
     *
     * ★ expiresAt 은 발급 때 이미 절대 상한 이하로 깎여 있어(min) 보통은 그 검사만으로 충분하다.
     *   그럼에도 absoluteExpiresAt 을 한 번 더 본다 — 깎는 로직에 구멍이 나도 상한만은 반드시 지키게
     *   하는 이중 방어다.
     */
    public boolean isUsable(Instant now) {
        if (revokedAt != null) {
            return false;
        }
        if (!now.isBefore(expiresAt)) {
            return false;
        }
        return absoluteExpiresAt == null || now.isBefore(absoluteExpiresAt);
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getAbsoluteExpiresAt() {
        return absoluteExpiresAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
