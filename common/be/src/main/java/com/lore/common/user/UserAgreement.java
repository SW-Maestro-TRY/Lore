package com.lore.common.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

import java.time.Instant;

/**
 * 약관·개인정보 동의 기록. 항목 하나, 판 하나마다 한 줄.
 *
 * ★ 판 번호(version)가 있는 이유 — 약관을 고치면 기존 회원에게 **다시 동의를 받아야** 하는데,
 *   판 번호가 없으면 "이 사람이 옛 판에 동의한 건지 새 판에 동의한 건지"를 알 수 없다.
 *   나중에 붙이려면 전원에게 다시 받아야 하므로 처음부터 넣는다.
 *
 * ★ 참/거짓이 아니라 시각도 함께 남긴다. "언제 동의했나"는 분쟁이 생겼을 때 유일한 근거다.
 */
@Entity
@Table(
        name = "user_agreement",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_agreement_user_type_version",
                columnNames = {"user_id", "type", "version"}))
public class UserAgreement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_agreement_user"))
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private AgreementType type;

    /** 동의한 문서의 판. 예: "2026-09-01" */
    @Column(nullable = false, length = 20)
    private String version;

    /** 선택 항목(마케팅)은 거부도 기록으로 남긴다 — 안 물어본 것과 거부한 것은 다르다. */
    @Column(nullable = false)
    private boolean agreed;

    @Column(nullable = false)
    private Instant agreedAt;

    protected UserAgreement() {
    }

    public static UserAgreement of(User user, AgreementType type, String version, boolean agreed, Instant now) {
        UserAgreement a = new UserAgreement();
        a.user = user;
        a.type = type;
        a.version = version;
        a.agreed = agreed;
        a.agreedAt = now;
        return a;
    }

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public AgreementType getType() {
        return type;
    }

    public String getVersion() {
        return version;
    }

    public boolean isAgreed() {
        return agreed;
    }

    public Instant getAgreedAt() {
        return agreedAt;
    }
}
