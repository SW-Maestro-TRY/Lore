package com.lore.webtoon.work;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 관리자가 남의 작품에 한 일 한 줄(#638) — 비공개 · 다시 공개 · 삭제 · 되살리기 · 경고.
 *
 * 지금 상태는 {@link WebtoonWork#getModeration()} 이 들고, 여기는 <b>지나간 일</b>을 쌓는다. 경고는 작품 상태를
 * 바꾸지 않으므로 여기에만 남는다. 작품이 영구 삭제돼도 이 줄은 남긴다 — 같은 작가가 몇 번 처리됐는지는
 * 작품이 사라진 뒤에도 알아야 한다.
 */
@Entity
@Table(name = "webtoon_moderation_log")
public class ModerationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false, length = 64)
    private String runId;

    /** 작품 주인 계정. 게스트 작품이면 비어 있다. */
    @Column(name = "owner_user_id")
    private Long ownerUserId;

    @Column(name = "admin_user_id", nullable = false)
    private Long adminUserId;

    /** HIDE · UNHIDE · REMOVE · RESTORE · WARN */
    @Column(name = "action", nullable = false, length = 16)
    private String action;

    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    /** 작가에게 메일이 갔나. */
    @Column(name = "notified", nullable = false)
    private boolean notified;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ModerationLog() {
    }

    static ModerationLog of(String runId, Long ownerUserId, Long adminUserId, String action, String reason, Instant at) {
        ModerationLog one = new ModerationLog();
        one.runId = runId;
        one.ownerUserId = ownerUserId;
        one.adminUserId = adminUserId;
        one.action = action;
        one.reason = reason == null ? "" : reason;
        one.createdAt = at;
        return one;
    }

    void markNotified() {
        this.notified = true;
    }

    public Long getId() {
        return id;
    }

    public String getRunId() {
        return runId;
    }

    public Long getOwnerUserId() {
        return ownerUserId;
    }

    public Long getAdminUserId() {
        return adminUserId;
    }

    public String getAction() {
        return action;
    }

    public String getReason() {
        return reason;
    }

    public boolean isNotified() {
        return notified;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
