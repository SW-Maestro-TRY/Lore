package com.lore.webtoon;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 누가 만든 작품인가.
 *
 * <h2>지금까지는 이 연결이 DB 에 없었다</h2>
 *
 * <pre>
 * users.id ──(webtoon_browser_link)──> browser_uid ──( ??? )──> run_id
 *    DB              DB                                  하네스 파일 두 개
 * </pre>
 *
 * 마지막 칸을 파일 두 개를 이어 붙여 만들고 있었다({@code landing/ownership.py}
 * — 저작권 확인 기록과 작업 폴더의 상태 파일). 그래서 세 가지가 따라왔다:
 *
 * <ul>
 *   <li><b>권한을 붙일 자리가 없다.</b> "이 작품이 내 것인가" 를 물으려면 그
 *       질문을 받을 표가 있어야 한다. 실제로 지금은 비공개 작품도 run_id 만
 *       알면 그림이 나온다 — 목록에서 가려질 뿐이다.</li>
 *   <li><b>하네스가 없으면 내 작품이 사라진다.</b> 파일이 거기 있으니까.</li>
 *   <li>이미지를 S3 로 옮겨도 이 연결이 없으면 여전히 못 묻는다.</li>
 * </ul>
 *
 * <h2>작업 번호로 먼저 적고, 작품 번호는 나중에 채운다</h2>
 *
 * 만들기를 시작하는 순간에는 <b>작업(job) 번호밖에 없다</b> — 작품(run) 번호는
 * 이야기를 만들고 나서야 생긴다. 그래서 시작할 때 작업 번호로 한 줄 적어 두고,
 * 진행 상황을 물을 때 따라오는 작품 번호를 그 줄에 채운다.
 *
 * 시작할 때 안 적고 끝나서 적으면, <b>중간에 죽은 작업은 주인이 없다.</b> 그런데
 * 그때도 돈은 이미 나갔고 그림이 몇 장 남아 있을 수 있다.
 *
 * <h2>게스트도 적는다</h2>
 *
 * 로그인 안 하고 만드는 것이 이 제품의 약속이라, {@code userId} 는 비어 있을 수
 * 있다. 대신 브라우저 값을 적어 두면 나중에 로그인할 때 계정에 이어 붙일 수
 * 있다({@link BrowserLink} 가 그 짝을 들고 있다).
 */
@Entity
@Table(
        name = "webtoon_work",
        indexes = {
                @Index(name = "idx_webtoon_work_user", columnList = "user_id"),
                @Index(name = "idx_webtoon_work_uid", columnList = "browser_uid"),
        })
public class WebtoonWork {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 만들기 작업 번호. 시작하는 순간 아는 유일한 값이라 이것으로 먼저 적는다.
     *
     * 유일하게 둔다 — 같은 작업이 두 줄이 되면 "누가 만들었나" 에 답이 둘이 된다.
     */
    @Column(name = "job_id", nullable = false, unique = true, length = 64)
    private String jobId;

    /**
     * 다 만들어진 작품 번호. 이야기가 만들어져야 생기므로 처음엔 비어 있다.
     *
     * <b>유일 제약을 안 건다.</b> 편집실의 「이어 만들기」처럼 한 작품에서 갈라져
     * 나오는 길이 있어서, 지금 안 겹친다고 영영 안 겹친다고 볼 수 없다. 겹치면
     * 그때 알아보는 편이 낫지, 저장이 실패해서 만들기가 멈추는 것보다 낫다.
     */
    @Column(name = "run_id", length = 64)
    private String runId;

    /** 로그인하고 만들었으면 그 계정. 게스트면 비어 있다. */
    @Column(name = "user_id")
    private Long userId;

    /** 어느 브라우저가 만들었나. 게스트를 나중에 계정에 이어 붙일 때 쓴다. */
    @Column(name = "browser_uid", nullable = false, length = 64)
    private String browserUid;

    /**
     * 둘러보기에 걸려 있나.
     *
     * <b>기본이 공개다.</b> 하네스 쪽 규칙과 같게 맞춘다(landing/visibility.py) —
     * 기본이 비공개면 둘러보기가 늘 비어서 처음 온 사람에게 고장난 화면으로
     * 보인다. 대신 만들기 마지막 걸음에서 미리 말하고 마이페이지에서 내릴 수
     * 있게 한다.
     *
     * 이 값이 <b>그림을 어디에 두는지</b>까지 정한다 — 공개는 CloudFront 가
     * 내주는 자리, 비공개는 안 내주는 자리(PrivateArt).
     */
    /* `default true` 를 꼭 적는다. 안 적으면 이미 줄이 있는 표에 "빈 값 금지"
       칸을 더하는 꼴이라, 그 자리에 넣을 값이 없어 컬럼 추가 자체가 실패한다
       (실제로 그랬다 — 이미 만든 작품 세 편이 있었다). 기본값이 있으면 옛
       줄이 그 값으로 채워지고, 공개가 기본이라 예전과 똑같이 보인다. */
    @Column(name = "is_public", nullable = false,
            columnDefinition = "boolean not null default true")
    private boolean isPublic = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected WebtoonWork() {
    }

    private WebtoonWork(String jobId, Long userId, String browserUid, Instant createdAt) {
        this.jobId = jobId;
        this.userId = userId;
        this.browserUid = browserUid;
        this.createdAt = createdAt;
    }

    static WebtoonWork started(String jobId, Long userId, String browserUid, Instant at) {
        return new WebtoonWork(jobId, userId, browserUid, at);
    }

    /** 이미 만들어져 있던 작품을 옮겨 담을 때. 작업 번호를 모르면 작품 번호로 쓴다. */
    static WebtoonWork moved(String jobId, String runId, Long userId,
                             String browserUid, Instant at) {
        WebtoonWork work = new WebtoonWork(jobId, userId, browserUid, at);
        work.runId = runId;
        return work;
    }

    /** 작품 번호를 알게 됐다. 이미 있으면 안 덮는다 — 나중 값이 더 맞을 이유가 없다. */
    void learnRun(String runId) {
        if (this.runId == null || this.runId.isBlank()) {
            this.runId = runId;
        }
    }

    /** 나중에 로그인해서 계정이 붙었다. 이미 주인이 있으면 안 뺏는다. */
    void claimBy(Long userId) {
        if (this.userId == null) {
            this.userId = userId;
        }
    }

    public Long getId() {
        return id;
    }

    public String getJobId() {
        return jobId;
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

    public Instant getCreatedAt() {
        return createdAt;
    }

    public boolean isPublic() {
        return isPublic;
    }

    void setPublic(boolean value) {
        this.isPublic = value;
    }
}
