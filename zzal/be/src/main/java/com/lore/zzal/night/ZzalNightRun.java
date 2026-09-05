package com.lore.zzal.night;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 밤 스위프 한 번의 기록 — <b>{@code night_of} 가 PK</b>라 같은 밤은 한 번만 돈다(플랜 T1 핵심 판단 2).
 *
 * <h3>★ 왜 PK 로 막나</h3>
 * 서버가 두 대이거나, 23:00 스위프 도중 재기동돼 기동 복구가 또 돌면 같은 밤을 두 번 굽는다(돈이 두 배).
 * "돌았나" 를 조회로 확인하고 INSERT 하면 그 사이에 다른 서버가 끼어든다. PK 충돌은 DB 가 직렬화해 준다 —
 * 두 번째 INSERT 는 실패하고, 실패한 쪽은 "이미 돌았다" 로 읽고 물러난다.
 *
 * <h3>끝나지 않은 run(finishedAt null)</h3>
 * 스위프 중 서버가 죽은 흔적. 기동 복구는 이 밤을 <b>새로 계획하지 않고</b> 남은 QUEUED 만 이어서 집는다
 * (계획은 이미 됐고, BAKING 은 굽다 죽은 것이라 StuckMotionRecovery 가 따로 잇는다).
 */
@Entity
@Table(name = "zzal_night_run")
public class ZzalNightRun {

    /** 그 밤의 KST 날짜(23:00 이 속한 날). */
    @Id
    @Column(name = "night_of", nullable = false)
    private LocalDate nightOf;

    @Column(nullable = false)
    private Instant startedAt;

    @Column
    private Instant finishedAt;

    /** 어느 서버가 돌렸나(호스트명). 여러 대일 때 로그 대조용. */
    @Column(length = 60)
    private String server;

    /** 이 밤에 새로 큐에 올린 수. */
    @Column(nullable = false, columnDefinition = "integer default 0")
    private int queued;

    /** 이 밤에 집어서 굽기 시작한 수(K 캡 안). */
    @Column(nullable = false, columnDefinition = "integer default 0")
    private int claimed;

    /** K 를 넘어 다음 밤으로 넘긴 수. */
    @Column(nullable = false, columnDefinition = "integer default 0")
    private int carried;

    protected ZzalNightRun() {
    }

    public static ZzalNightRun start(LocalDate nightOf, Instant now, String server) {
        ZzalNightRun r = new ZzalNightRun();
        r.nightOf = nightOf;
        r.startedAt = now;
        r.server = server;
        return r;
    }

    public void finish(Instant now, int queued, int claimed, int carried) {
        this.finishedAt = now;
        this.queued = queued;
        this.claimed = claimed;
        this.carried = carried;
    }

    public boolean isFinished() {
        return finishedAt != null;
    }

    public LocalDate getNightOf() {
        return nightOf;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public String getServer() {
        return server;
    }

    public int getQueued() {
        return queued;
    }

    public int getClaimed() {
        return claimed;
    }

    public int getCarried() {
        return carried;
    }
}
