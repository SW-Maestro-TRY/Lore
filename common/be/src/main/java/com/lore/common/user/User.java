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

    /** 가입 시 기본으로 주는 펫 칸 수. */
    public static final int DEFAULT_PET_SLOTS = 1;

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

    /**
     * 키울 수 있는 펫 수. 기본 1 마리.
     *
     * ★ 상수로 박지 않고 칸으로 둔 이유 — 나중에 유료로 칸을 늘려 팔 예정이라
     *   사람마다 값이 달라진다(2026-09-02 결정). 판정은 이 값과 비교하는 한 줄이면 된다.
     *   혜택이 여러 개(워터마크 제거·투명배경 등)로 늘면 그때 별도 표로 옮긴다.
     *
     * ★ columnDefinition 으로 기본값을 함께 준 이유 — 이미 계정이 있는 표에
     *   "비어 있으면 안 되는 칸" 을 그냥 추가하면 **기존 행을 어떻게 채울지 몰라 DB 가 거부한다.**
     *   (2026-09-02 실제로 이 에러가 났고, 서버는 정상적으로 떴는데 칼럼만 안 만들어져 있었다)
     *   운영에서 사용자가 있는 상태로 칼럼을 늘릴 때 항상 겪는 일이라, 기본값을 같이 준다.
     */
    @Column(nullable = false, columnDefinition = "integer default 1")
    private int petSlots;

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
        this.petSlots = DEFAULT_PET_SLOTS;
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

    public int getPetSlots() {
        return petSlots;
    }
}
