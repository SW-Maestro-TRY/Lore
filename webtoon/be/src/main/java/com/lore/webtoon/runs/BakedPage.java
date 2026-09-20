package com.lore.webtoon.runs;

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
 * 얹은 것을 <b>구워 넣은</b> 한 장이 S3 어디에 있나.
 *
 * <h2>왜 원본과 따로 두는가</h2>
 *
 * 편집실은 <b>밑그림</b>을 봐야 한다({@code raw=1}) — 구운 판을 보여 주면 얹은
 * 말풍선이 두 겹으로 보인다. 그래서 원본({@code webtoon_page})은 그대로 두고
 * 구운 것만 여기에 따로 적는다. 다시 구우면 이 줄만 바뀌고 원본은 안 건드린다.
 *
 * 보는 자리(완성본 · 둘러보기 · 내려받기)는 <b>구운 것이 있으면 그것</b>을
 * 쓴다. 얹어 놓고 받았더니 말풍선이 없더라는 것이 가장 알아채기 어려운 실패다.
 */
@Entity
@Table(name = "webtoon_baked_page",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_webtoon_baked_page",
                columnNames = {"run_id", "page_no", "width"}),
        indexes = @Index(name = "idx_webtoon_baked_page_run", columnList = "run_id"))
public class BakedPage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false, length = 120)
    private String runId;

    @Column(name = "page_no", nullable = false)
    private int pageNo;

    /** 어느 폭으로 구웠나. 원본과 같은 값을 쓴다(320 · 1080 …). */
    @Column(name = "width", nullable = false)
    private int width;

    @Column(name = "s3_key", nullable = false, length = 300)
    private String s3Key;

    @Column(name = "baked_at", nullable = false)
    private Instant bakedAt;

    protected BakedPage() {
    }

    static BakedPage of(String runId, int pageNo, int width, String s3Key, Instant at) {
        BakedPage one = new BakedPage();
        one.runId = runId;
        one.pageNo = pageNo;
        one.width = width;
        one.s3Key = s3Key;
        one.bakedAt = at;
        return one;
    }

    void movedTo(String s3Key, Instant at) {
        this.s3Key = s3Key;
        this.bakedAt = at;
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
}
