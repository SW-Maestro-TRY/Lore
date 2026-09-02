package com.lore.common.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/**
 * 서비스 안의 사람. **세 도메인(zzal·webtoon·trailer)이 공유하는 기둥이다.**
 *
 * 크레딧도, 펫도, 웹툰도 전부 이 번호만 바라본다. 그래서 이 표는 앞으로 거의 바뀌지 않아야 하고,
 * 자주 바뀌거나 늘어나는 것은 옆에 붙는 별도 표로 뺀다.
 *
 * ★ 비밀번호가 여기 없는 이유 — 로그인 수단은 {@link UserCredential} 로 분리했다.
 *   계정 표 안에 비밀번호 칸을 두면, 나중에 구글·카카오 로그인을 붙일 때
 *   **가장 많은 것이 매달린 이 표를 뜯어야 한다**(그 순간 크레딧까지 같이 흔들린다).
 *   수단을 따로 두면 소셜 로그인은 행 하나 추가로 끝나고, 한 사람이 여러 수단을 갖는 것도 자연스럽다.
 */
@Entity
@Table(name = "users", uniqueConstraints = @UniqueConstraint(name = "uk_users_email", columnNames = "email"))
@EntityListeners(AuditingEntityListener.class)
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserRole role;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private Instant updatedAt;

    /**
     * 탈퇴 표시. 실제 삭제는 30일 뒤에 한다(2026-09-01 결정).
     *
     * 즉시 지우면 실수로 탈퇴한 사람의 펫과 모션이 영영 사라지고 되돌릴 수 없다.
     * 참/거짓 대신 시각을 쓰는 이유는 "언제 탈퇴했나"까지 한 칸으로 답하기 위함이다.
     */
    @Column
    private Instant deletedAt;

    protected User() {
    }

    private User(String email) {
        this.email = email;
        this.status = UserStatus.ACTIVE;
        this.role = UserRole.USER;
    }

    public static User signUp(String email) {
        return new User(email);
    }

    /** 탈퇴. 행을 지우지 않고 표시만 남긴다. */
    public void withdraw(Instant now) {
        this.status = UserStatus.DELETED;
        this.deletedAt = now;
    }

    public boolean isActive() {
        return status == UserStatus.ACTIVE;
    }

    public boolean isAdmin() {
        return role == UserRole.ADMIN;
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public UserStatus getStatus() {
        return status;
    }

    public UserRole getRole() {
        return role;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }
}
