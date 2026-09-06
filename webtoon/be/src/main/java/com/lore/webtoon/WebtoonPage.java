package com.lore.webtoon;

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
 * 작품 한 장이 S3 어디에 있나.
 *
 * <h2>파일이 아니라 주소만 둔다</h2>
 *
 * 그림 자체는 S3 에 있고 여기 있는 것은 <b>그 자리를 가리키는 글자 하나</b>다.
 * 화면은 이 주소로 CloudFront 에서 바로 읽고, 하네스는 만드는 일만 한다.
 *
 * 이게 없던 동안 그림은 하네스 디스크에만 있었다 — 그래서 하네스가 없는
 * 실서버에서는 그림도 없었고, 그 자리를 공개본 세 편을 빌드에 구워 넣은
 * 임시 전시판이 메우고 있었다(새 작품마다 다시 뽑아 커밋해야 해서 확장이
 * 안 된다).
 *
 * <h2>폭마다 한 줄이다</h2>
 *
 * 원본만 두면 폭을 줄여 줄 서버가 여전히 필요해서 지금 문제가 그대로 남는다.
 * 표지(320)와 본문(1080)을 미리 만들어 올려 두면 화면이 S3 만 보고 끝난다.
 * {@code width = 0} 이 원본이다 — 편집실에서 다시 굽거나 나중에 다른 폭이
 * 필요할 때 쓴다.
 *
 * <h2>주소를 못 맞힌다</h2>
 *
 * 키는 {@code images/webtoon/<임의의 이름>} 이다(이 저장소가 이미 쓰는 규칙 —
 * common 의 S3Service). 작품 번호로 짓지 않는 이유: 그러면 번호를 아는 사람이
 * 비공개 작품의 그림 주소까지 지어낼 수 있다. 어느 그림이 어느 장인지는 이
 * 표만 안다.
 *
 * 그래도 <b>주소를 손에 넣으면 누구나 열린다</b>. CloudFront 의 {@code /images/*}
 * 는 열려 있다. 제대로 막는 것은 다음 일이고, 지금은 맞히기 어렵게 해 두는
 * 데까지다 — 공유 링크도 이미 그 수준이다.
 */
@Entity
@Table(
        name = "webtoon_page",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_webtoon_page_one",
                columnNames = {"run_id", "page_no", "width"}),
        indexes = @Index(name = "idx_webtoon_page_run", columnList = "run_id"))
public class WebtoonPage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false, length = 64)
    private String runId;

    @Column(name = "page_no", nullable = false)
    private int pageNo;

    /** 몇 픽셀 폭으로 줄인 것인가. {@code 0} 은 원본. */
    @Column(nullable = false)
    private int width;

    @Column(name = "s3_key", nullable = false, length = 200)
    private String s3Key;

    /** 파일 크기. 얼마나 쌓이는지 보려고 같이 적는다. */
    @Column(nullable = false)
    private long bytes;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected WebtoonPage() {
    }

    private WebtoonPage(String runId, int pageNo, int width,
                        String s3Key, long bytes, Instant createdAt) {
        this.runId = runId;
        this.pageNo = pageNo;
        this.width = width;
        this.s3Key = s3Key;
        this.bytes = bytes;
        this.createdAt = createdAt;
    }

    static WebtoonPage of(String runId, int pageNo, int width,
                          String s3Key, long bytes, Instant at) {
        return new WebtoonPage(runId, pageNo, width, s3Key, bytes, at);
    }

    /** 다시 구워 새로 올렸다. 같은 자리에 다른 그림이 온 것이라 주소를 바꾼다. */
    void movedTo(String s3Key, long bytes, Instant at) {
        this.s3Key = s3Key;
        this.bytes = bytes;
        this.createdAt = at;
    }

    public Long getId() {
        return id;
    }

    public String getRunId() {
        return runId;
    }

    public int getPageNo() {
        return pageNo;
    }

    public int getWidth() {
        return width;
    }

    public String getS3Key() {
        return s3Key;
    }

    public long getBytes() {
        return bytes;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
