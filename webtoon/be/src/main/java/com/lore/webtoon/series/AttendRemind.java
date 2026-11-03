package com.lore.webtoon.series;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 「출석으로 모아서 다음 화 만들기」를 고른 기록. 한 줄이 한 사람이다
 * ({@code V20261103_1940__webtoon_attend_remind.sql}).
 */
@Entity
@Table(name = "webtoon_attend_remind")
public class AttendRemind {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "run_id", nullable = false, length = 64)
    private String runId;

    @Column(nullable = false)
    private int episode;

    @Column(nullable = false)
    private int cost;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_sent_on")
    private LocalDate lastSentOn;

    @Column(name = "done_at")
    private Instant doneAt;

    protected AttendRemind() {
    }

    public AttendRemind(Long userId) {
        this.userId = userId;
    }

    /** 이 화로 다시 건다. 앞서 보낸 기록은 지운다 — 새로 고른 것이다. */
    void plan(String runId, int episode, int cost, Instant now) {
        this.runId = runId;
        this.episode = episode;
        this.cost = cost;
        this.createdAt = now;
        this.lastSentOn = null;
        this.doneAt = null;
    }

    void sentOn(LocalDate day) {
        this.lastSentOn = day;
    }

    void done(Instant at) {
        this.doneAt = at;
    }

    public Long getUserId() { return userId; }
    public String getRunId() { return runId; }
    public int getEpisode() { return episode; }
    public int getCost() { return cost; }
    public Instant getCreatedAt() { return createdAt; }
    public LocalDate getLastSentOn() { return lastSentOn; }
    public Instant getDoneAt() { return doneAt; }
    public boolean isActive() { return doneAt == null; }
}
