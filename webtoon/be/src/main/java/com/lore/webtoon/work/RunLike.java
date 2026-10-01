package com.lore.webtoon.work;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/**
 * 작품 찜 한 줄 — 누가 어느 작품을 찜했나(#247).
 *
 * 계정당 작품 하나에 한 줄이다. 왜 로그인한 사람만 찜할 수 있는지는 표를 만드는 SQL
 * ({@code V20260926_0109__webtoon_run_like.sql}) 머리에 있다.
 */
@Entity
@Table(
        name = "webtoon_run_like",
        uniqueConstraints = @UniqueConstraint(name = "uk_webtoon_run_like", columnNames = {"run_id", "user_id"}),
        indexes = @Index(name = "idx_webtoon_run_like_user", columnList = "user_id, created_at"))
public class RunLike {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false, length = 64)
    private String runId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected RunLike() {
    }

    public RunLike(String runId, Long userId, Instant createdAt) {
        this.runId = runId;
        this.userId = userId;
        this.createdAt = createdAt;
    }

    public String getRunId() {
        return runId;
    }

    public Long getUserId() {
        return userId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
