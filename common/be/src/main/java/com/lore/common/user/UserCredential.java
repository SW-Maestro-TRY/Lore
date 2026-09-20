package com.lore.common.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/**
 * 로그인하는 방법. 한 사람이 여러 개를 가질 수 있다.
 *
 * ★ 계정에서 떼어낸 이유 — 지금은 이메일+비밀번호 하나뿐이지만, 구글·카카오가 붙을 때
 *   **행 하나 추가로 끝나게** 하려는 것이다. 계정 표 안에 비밀번호를 두면 그때 그 표를 뜯어야 하고,
 *   그 표에는 크레딧·펫·웹툰이 전부 매달려 있다.
 *   (자캐 사용자층은 트위터 비중도 커서 수단이 늘어날 가능성이 높다)
 *
 * ★ 비밀번호는 원문이 아니라 BCrypt 해시로만 저장한다. 직접 구현하면 거의 반드시 취약해지는
 *   영역이라 스프링이 제공하는 검증된 구현을 쓴다.
 */
@Entity
@Table(
        name = "user_credential",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_credential_user_provider", columnNames = {"user_id", "provider"}),
                @UniqueConstraint(name = "uk_credential_provider_uid", columnNames = {"provider", "provider_user_id"})
        })
@EntityListeners(AuditingEntityListener.class)
public class UserCredential {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_credential_user"))
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AuthProvider provider;

    /** LOCAL 일 때만 채워진다. BCrypt 해시. */
    @Column(length = 100)
    private String passwordHash;

    /** 소셜 로그인일 때 그쪽이 주는 고유 번호. LOCAL 은 비어 있다. */
    @Column(name = "provider_user_id", length = 255)
    private String providerUserId;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private Instant updatedAt;

    protected UserCredential() {
    }

    /** 이메일+비밀번호 수단. 해시는 서비스 계층에서 만들어 넘긴다(엔티티가 암호화를 알 필요는 없다). */
    public static UserCredential local(User user, String passwordHash) {
        UserCredential c = new UserCredential();
        c.user = user;
        c.provider = AuthProvider.LOCAL;
        c.passwordHash = passwordHash;
        return c;
    }

    public void changePassword(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public AuthProvider getProvider() {
        return provider;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getProviderUserId() {
        return providerUserId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
