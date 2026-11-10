package com.lore.zzal.night;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 밤 스위프 한 번의 기록 — <b>{@code (night_of, time_zone)} 이 PK</b>라 한 시간대의 같은 밤은 한 번만 돈다(플랜 T1 핵심 판단 2).
 *
 * <h3>★ 2026-10-26 시간대 2단계 — 왜 시간대가 키에 들어갔나</h3>
 * 펫마다 시간대가 생겨 "23:00" 이 시간대마다 다른 순간이다. 15분마다 도는 스위프가 그 시간대의 23:00 을 지난
 * 펫들을 한 판으로 묶어 돌리고, 이 행이 그 판의 기록이다. 옛 행은 전부 {@code Asia/Seoul} 로 채워졌다.
 * ★ 펫 단위의 "그 밤을 이미 돌았나" 는 이 행이 아니라 {@code zzal_pet.last_swept_night_of} 가 막는다 —
 *   시간대를 처음 관측한 펫이 KST 밤을 돈 뒤 같은 날짜의 현지 밤을 또 도는 것을 막는 것은 펫 단위여야 한다.
 *
 * <h3>★ 왜 PK 로 막나</h3>
 * 서버가 두 대이거나, 23:00 스위프 도중 재기동돼 기동 복구가 또 돌면 같은 밤을 두 번 굽는다(돈이 두 배).
 * "돌았나" 를 조회로 확인하고 INSERT 하면 그 사이에 다른 서버가 끼어든다. PK 충돌은 DB 가 직렬화해 준다 —
 * 두 번째 INSERT 는 실패하고, 실패한 쪽은 "이미 돌았다" 로 읽고 물러난다.
 *
 * <h3>끝나지 않은 run(finishedAt null)</h3>
 * 스위프 중 서버가 죽은 흔적. 기동 복구는 이 밤의 계획을 <b>다시 돌린 뒤</b>(계획 도중에 죽었으면 뒤쪽 펫이 통째로
 * 빠지므로 — {@code NightPlanner.plan} 은 멱등이다) 남은 QUEUED 를 집는다. {@code BAKING} 인 채 죽은 자리는
 * {@code StuckMotionRecovery} 가 <b>QUEUED 로 되돌려</b> 같은 길에 태운다.
 *
 * <h3>★ {@code finishedAt} 의 뜻 — "굽기 완료" 가 아니라 "집기 완료"</h3>
 * 굽기는 실행기에서 밤새 돈다. 이 시각은 <b>그 밤에 집어서 실행기에 넘기는 일이 끝난 순간</b>이고,
 * {@code claimed} 도 "집은 수" 다(굽기 성공 수가 아니다). 그 밤이 실제로 어떻게 됐는지는
 * 관리자 {@code GET /night/summary}(모션 행을 직접 센다)로 본다. 끝난 run 뒤에 회수된 자리가 생기면
 * 다음 기동 복구가 집고 {@link #addClaimed(int)} 로 더한다.
 */
@Entity
@Table(name = "zzal_night_run")
@IdClass(ZzalNightRun.Key.class)
public class ZzalNightRun {

    /** 복합 키 — 밤 날짜(그 시간대의 날짜) + 시간대 이름. */
    public record Key(LocalDate nightOf, String timeZone) implements Serializable {
        public Key {
            if (timeZone == null) timeZone = com.lore.zzal.pet.ZzalRules.ZONE.getId();
        }

        /** JPA 가 부르는 빈 생성자. */
        public Key() {
            this(null, null);
        }

        public static Key seoul(LocalDate nightOf) {
            return new Key(nightOf, com.lore.zzal.pet.ZzalRules.ZONE.getId());
        }
    }

    /** 그 밤의 날짜(그 시간대에서 23:00 이 속한 날). */
    @Id
    @Column(name = "night_of", nullable = false)
    private LocalDate nightOf;

    /** 그 판의 시간대(IANA 이름). 옛 행은 전부 Asia/Seoul. */
    @Id
    @Column(name = "time_zone", nullable = false, length = 64)
    private String timeZone;

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

    /** 이 밤에 집어서 실행기에 넘긴 수(K 캡 안). 굽기 성공 수가 아니다. */
    @Column(nullable = false, columnDefinition = "integer default 0")
    private int claimed;

    /** K 에 걸려 손도 안 대고 다음 밤으로 넘긴 수(집기에 진 것은 안 센다). */
    @Column(nullable = false, columnDefinition = "integer default 0")
    private int carried;

    protected ZzalNightRun() {
    }

    public static ZzalNightRun start(LocalDate nightOf, Instant now, String server) {
        return start(nightOf, com.lore.zzal.pet.ZzalRules.ZONE.getId(), now, server);
    }

    public static ZzalNightRun start(LocalDate nightOf, String timeZone, Instant now, String server) {
        ZzalNightRun r = new ZzalNightRun();
        r.nightOf = nightOf;
        r.timeZone = timeZone;
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

    /** 끝난 밤 뒤에 회수된 자리를 더 집었을 때(기동 복구). 통계가 실제와 어긋나지 않게 더해만 준다. */
    public void addClaimed(int more) {
        this.claimed += more;
    }

    public boolean isFinished() {
        return finishedAt != null;
    }

    public LocalDate getNightOf() {
        return nightOf;
    }

    public String getTimeZone() {
        return timeZone;
    }

    public Key key() {
        return new Key(nightOf, timeZone);
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
