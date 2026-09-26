package com.lore.webtoon.job;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 웹툰 한 편을 만드는 일.
 *
 * <h2>왜 DB 로 옮기나</h2>
 *
 * 지금 이 상태는 파이썬 서버의 <b>메모리와 파일</b>에 있다
 * ({@code NHRunner.jobs} + {@code jobs_nh/<id>/state.json}). 그래서:
 *
 * <ul>
 *   <li>그 서버가 죽으면 만들던 것이 어디까지 갔는지 아무도 모른다.
 *       돈은 이미 나간 뒤다.</li>
 *   <li>서버가 둘이라(스프링 · 파이썬) 상태가 두 곳으로 갈린다. 크레딧과
 *       작품은 DB 로 옮겼는데 <b>정작 그것을 만드는 일</b>은 저쪽에 있다.</li>
 *   <li>여러 대로 늘릴 수가 없다. 메모리에 든 대기열은 그 프로세스 것이다.</li>
 * </ul>
 *
 * 옆 도메인(zzal)은 파이썬 서버 없이 스프링이 파이썬을 하위 프로세스로 부르고
 * 상태는 DB 에 둔다({@code zzal_gen_job}). 웹툰도 그리로 간다.
 *
 * <h2>작품 번호는 나중에 생긴다</h2>
 *
 * 만들기를 시작할 때는 이 일의 번호밖에 없다. 작품 번호는 첫 단계(이야기
 * 후보 만들기)가 끝나야 파이썬이 지어 준다. 그래서 {@code runId} 는 비어 있을
 * 수 있고, 알게 되면 그때 채운다 — {@code webtoon_work} 와 같은 규칙이다.
 */
@Entity
@Table(
        name = "webtoon_job",
        indexes = {
                @Index(name = "idx_webtoon_job_status", columnList = "status, id"),
                @Index(name = "idx_webtoon_job_user", columnList = "user_id"),
        })
public class WebtoonJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 화면이 부르는 번호. 숫자 id 를 그대로 내보내지 않는다 — 남의 번호를
     * 하나씩 세어 볼 수 있게 된다.
     */
    @Column(name = "public_id", nullable = false, unique = true, length = 40)
    private String publicId;

    @Column(name = "run_id", length = 64)
    private String runId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "browser_uid", nullable = false, length = 64)
    private String browserUid;

    /**
     * 로그인 안 한 사람의 하루 몫을 가리키는 열쇠.
     *
     * 만들기는 몇 분 뒤에 실패할 수 있는데, 그때는 요청이 없어서 그 사람이
     * 누구였는지 알 길이 없다. 그러면 <b>만든 것도 없는데 오늘 몫만 줄어</b>
     * 있게 된다. 되돌릴 수 있게 여기 남긴다. 로그인한 사람은 비어 있다
     * (그쪽은 크레딧으로 센다).
     */
    @Column(name = "guest_key", length = 80)
    private String guestKey;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private JobStatus status;

    /** 지금 어느 단계인가. 화면이 이걸로 진행률을 그린다. */
    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private JobStage stage;

    /** 어느 그림체로. 파이썬에 {@code NH_STYLE} 로 넘어간다. */
    @Column(nullable = false, length = 30)
    private String style;

    /**
     * 얼마나 촘촘히. 파이썬에 {@code OPENAI_IMAGE_QUALITY} 로 넘어간다.
     *
     * <b>작업에 남겨 둬야 하는 이유</b> — 한 장 다시 그릴 때 같은 화질로
     * 그려야 한다. 안 남기면 그 장만 다른 화질로 나와서 한 편 안에서 밀도가
     * 갈린다. 그림체를 {@code style.txt} 에 남기는 것과 같은 이유다.
     *
     * 옛 작업에는 값이 없다 — 그래서 nullable 이고, 읽는 쪽이
     * {@link WebtoonQuality#normalize} 로 기본값으로 돌린다.
     */
    @Column(length = 20)
    private String quality;

    /**
     * 사람이 중간에 멈춰 서서 볼 것인가.
     *
     * 거짓이면 시트도 이야기도 서버가 알아서 고르고 끝까지 간다
     * (「빠르게 결과부터」). 참이면 두 자리에서 멈춘다.
     */
    @Column(nullable = false)
    private boolean checkpoints;

    /** 고른 이야기 번호. 아직 안 골랐으면 비어 있다. */
    @Column(name = "picked")
    private Integer picked;

    /**
     * 만들 때 사람이 넣은 것 — 이름 · 설명 · 장르 · 어떤 이야기를 원했나.
     *
     * <b>결과만 남기면 "이 입력이 좋은 결과를 냈나" 를 물을 수가 없다.</b>
     * 지금까지 이건 작업 폴더의 character.json 에만 있었다 — 그 폴더가
     * 없어지면 무엇으로 만든 작품인지 아무도 모른다.
     *
     * 통째로 JSON 으로 둔다. 칸으로 쪼개면 폼이 바뀔 때마다 표를 고쳐야 하고,
     * 제품이 이 값으로 하는 일은 <b>나중에 들여다보는 것</b>뿐이다.
     * 사진은 안 넣는다 — 사람 얼굴이 들어올 수 있는 값이라 여기 쌓을 것이 아니다.
     */
    @Column(name = "input_json", columnDefinition = "text")
    private String inputJson;

    /** 왜 실패했나. 사람이 읽을 한 줄. */
    @Column(length = 300)
    private String error;

    /**
     * 실패했을 때 실제로 돌려준 것. 화면이 안내의 마지막 한 줄을 고른다.
     * 끝나지 않았거나 잘 끝난 작업에서는 비어 있다.
     */
    @Column(name = "refunded", length = 10)
    @Enumerated(EnumType.STRING)
    private Refunded refunded;

    /**
     * <b>줄에서 빠져나와 실제로 돌기 시작한 때.</b>
     *
     * 만든 때({@code createdAt})와의 차이가 <b>기다린 시간</b>이다. 이걸 안
     * 적으면 "얼마나 기다렸나" 를 영영 못 잰다 — 만들기는 한 번에 한 편씩
     * 도는데 그게 실제로 얼마나 밀리는지 아무 데도 안 남아 있었다. 병렬로
     * 갈지 말지는 <b>이 숫자를 보고</b> 정할 일이다(추측 말고).
     */
    @Column(name = "started_at")
    private Instant startedAt;

    /**
     * 끝난 때 — 다 됐거나 실패했거나.
     *
     * {@code startedAt} 과의 차이가 실제로 만든 시간이다. 다만 사람이 시트·
     * 이야기 앞에서 멈춰 선 시간이 여기 섞인다({@code AWAITING_*}) — 순수
     * 기계 시간은 {@code meta.json} 의 걸음별 초를 봐야 한다.
     */
    @Column(name = "finished_at")
    private Instant finishedAt;

    /**
     * 줄 설 때 <b>앞에 몇 개</b> 있었나. 화면에 「앞에 3명」이라고 적은 그 숫자다.
     *
     * 나중에 이 값과 실제로 기다린 시간을 맞춰 보면 <b>우리가 적어 준 예상이
     * 맞았는지</b> 알 수 있다. 예상이 틀리면 사람은 두 번 속는다 — 기다린 것과
     * 속은 것.
     */
    @Column(name = "queued_ahead")
    private Integer queuedAhead;

    /**
     * 다 만들어지면 <b>어디로</b> 알릴 것인가. 게스트가 직접 적어 넣은 주소다.
     *
     * <b>로그인한 사람은 여기가 비어 있다</b> — 보낼 때 계정에서 읽는다.
     * 베껴 두면 사람이 계정 이메일을 바꾼 뒤에도 옛 주소로 나간다.
     *
     * 비어 있으면 안 보낸다. 이건 <b>선택</b>이지 만들기의 조건이 아니다 —
     * 안 적어도 만들기는 그대로 돈다.
     */
    @Column(name = "notify_email", length = 255)
    private String notifyEmail;

    /**
     * 알림을 <b>보낸 때.</b> 이 칸이 두 번 보내는 것을 막는다.
     *
     * 끝나는 자리가 여럿이고(다 됨 · 실패 · 되살리기) 화면은 0.8초마다
     * 묻는다. 안 적으면 같은 사람에게 같은 메일이 여러 통 나간다.
     *
     * <b>여기 적는 일은 자바가 아니라 DB 한 문장이 한다</b>
     * ({@code WebtoonJobRepository.claimNotice}). 읽고-판단하고-쓰면 그
     * 사이의 틈에 둘이 같이 들어오기 때문이다.
     */
    @Column(name = "notified_at")
    private Instant notifiedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WebtoonJob() {
    }

    private WebtoonJob(String publicId, Long userId, String browserUid, String guestKey,
                       String style, String quality, boolean checkpoints,
                       String inputJson, Instant at) {
        this.publicId = publicId;
        this.guestKey = guestKey;
        this.userId = userId;
        this.browserUid = browserUid;
        this.style = style;
        this.quality = quality;
        this.checkpoints = checkpoints;
        this.inputJson = inputJson;
        this.status = JobStatus.QUEUED;
        this.stage = JobStage.STORY;
        this.createdAt = at;
        this.updatedAt = at;
    }

    public static WebtoonJob queued(String publicId, Long userId, String browserUid,
                                    String guestKey, String style, String quality,
                                    boolean checkpoints, String inputJson, Instant at) {
        return new WebtoonJob(publicId, userId, browserUid, guestKey,
                style, quality, checkpoints, inputJson, at);
    }

    /**
     * 예시 작품의 작업 줄 — <b>처음부터 끝난 것</b>으로 만든다.
     *
     * 예시는 실제로 도는 작업이 아니다. QUEUED 로 두면 영영 안 끝나 줄에 서
     * 있게 되고, 모든 사람에게 「앞에 대기자 9명」이 뜨며 하루 비용 상한도 그만큼
     * 미리 잡힌다({@code JobQueue.ahead}·{@code reserved}).
     */
    public static WebtoonJob seeded(String publicId, String browserUid, String style,
                                    String inputJson, Instant at) {
        WebtoonJob job = new WebtoonJob(publicId, null, browserUid, null,
                style, null, false, inputJson, at);
        job.status = JobStatus.DONE;
        job.stage = JobStage.BIND;
        job.finishedAt = at;
        return job;
    }

    void moveTo(JobStatus status, JobStage stage, Instant at) {
        /* **처음 돌기 시작한 때만 적는다.** 사람이 시트 앞에서 멈췄다가 다시
           가면 RUNNING 이 또 되는데, 그때 덮어쓰면 기다린 시간이 0 에 가깝게
           찍혀서 "아무도 안 기다렸다" 가 된다. */
        if (status == JobStatus.RUNNING && this.startedAt == null) {
            this.startedAt = at;
        }
        if (status.isOver() && this.finishedAt == null) {
            this.finishedAt = at;
        }
        /* **다 됐으면 실패 사유를 지운다.** 서버를 하나 더 띄우면 StaleJobs 가
           다른 서버에서 아직 도는 작업을 「서버가 다시 시작되어…」로 적는데,
           그 작업은 실제로 끝까지 가서 DONE 이 된다. 사유가 남으면 다 된 작품에
           실패 문구가 붙는다(2026-09-26 로컬에서 봄). */
        if (status == JobStatus.DONE) {
            this.error = null;
        }
        this.status = status;
        this.stage = stage;
        this.updatedAt = at;
    }

    /**
     * 다 되면 이 주소로 알린다. 빈 값을 주면 <b>안 받겠다</b>는 뜻이다.
     *
     * 아직 안 보냈으면 주소를 바꿀 수 있다 — 오타를 냈을 때 고칠 길이
     * 없으면 그 사람은 영영 못 받는다.
     */
    void notifyTo(String email, Instant at) {
        this.notifyEmail = (email == null || email.isBlank()) ? null : email.trim();
        this.updatedAt = at;
    }

    void failed(String why, Refunded refunded, Instant at) {
        if (this.finishedAt == null) {
            this.finishedAt = at;
        }
        this.status = JobStatus.ERROR;
        this.error = why == null ? null : why.substring(0, Math.min(why.length(), 300));
        this.refunded = refunded;
        this.updatedAt = at;
    }

    void learnRun(String runId, Instant at) {
        if (this.runId == null || this.runId.isBlank()) {
            this.runId = runId;
            this.updatedAt = at;
        }
    }

    void pick(int n, Instant at) {
        this.picked = n;
        this.updatedAt = at;
    }

    /**
     * 고른 것을 지운다 — 후보를 <b>다시</b> 지었을 때.
     *
     * 안 지우면 지난번에 고른 번호가 그대로 남아, 새 후보를 보여 주는 화면이
     * 셋째 칸에 이미 고른 표시를 달고 뜬다. 사람은 고른 적이 없다.
     */
    void unpick(Instant at) {
        this.picked = null;
        this.updatedAt = at;
    }

    public Long getId() {
        return id;
    }

    public String getPublicId() {
        return publicId;
    }

    public String getRunId() {
        return runId;
    }

    public Long getUserId() {
        return userId;
    }

    public String getBrowserUid() {
        return browserUid;
    }

    public String getGuestKey() {
        return guestKey;
    }

    public JobStatus getStatus() {
        return status;
    }

    public JobStage getStage() {
        return stage;
    }

    public String getStyle() {
        return style;
    }


    /** 어느 화질로. 옛 작업은 비어 있다 — 읽는 쪽이 기본값으로 돌린다. */
    public String getQuality() {
        return quality;
    }
    public boolean isCheckpoints() {
        return checkpoints;
    }

    public Integer getPicked() {
        return picked;
    }

    public String getInputJson() {
        return inputJson;
    }

    public String getError() {
        return error;
    }

    public Refunded getRefunded() {
        return refunded;
    }

    /** 줄에서 빠져나와 돌기 시작한 때. 아직 줄에 있으면 {@code null}. */
    public Instant getStartedAt() {
        return startedAt;
    }

    /** 끝난 때. 아직 도는 중이면 {@code null}. */
    public Instant getFinishedAt() {
        return finishedAt;
    }

    /** 줄 설 때 앞에 몇 개 있었나. 옛 작업은 {@code null}. */
    public Integer getQueuedAhead() {
        return queuedAhead;
    }

    /** 게스트가 적어 넣은 알림 주소. 로그인한 사람은 {@code null}. */
    public String getNotifyEmail() {
        return notifyEmail;
    }

    /** 알림을 보낸 때. 아직 안 보냈으면 {@code null}. */
    public Instant getNotifiedAt() {
        return notifiedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
