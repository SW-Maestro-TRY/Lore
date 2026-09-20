package com.lore.webtoon.art;

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
