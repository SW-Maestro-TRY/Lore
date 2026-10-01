package com.lore.webtoon.job;

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
 * 조연 캐릭터 시트 한 장(#548) — 인물 단계가 세운 다른 인물을 시트로 뽑은 기록.
 *
 * 주인공 시트는 무료로 작품 폴더에 하나뿐이지만, 조연은 한 장에 크레딧 1을 받는다.
 * 그래서 어느 작업에서 누구를 뽑았는지를 남겨 같은 이름을 두 번 받지 않고, 그림을
 * 창고에 올린 키도 남긴다(작품 폴더는 배포 서버에서 나중에 치워진다).
 */
@Entity
@Table(
        name = "webtoon_cast_sheet",
        uniqueConstraints = @UniqueConstraint(name = "uk_webtoon_cast_sheet", columnNames = {"job_id", "name"}),
        indexes = @Index(name = "idx_webtoon_cast_sheet_job", columnList = "job_id"))
public class WebtoonCastSheet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_id", nullable = false, length = 64)
    private String jobId;

    @Column(name = "name", nullable = false, length = 80)
    private String name;

    @Column(name = "s3_key", length = 255)
    private String s3Key;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected WebtoonCastSheet() {
    }

    public WebtoonCastSheet(String jobId, String name, String s3Key, Instant createdAt) {
        this.jobId = jobId;
        this.name = name;
        this.s3Key = s3Key;
        this.createdAt = createdAt;
    }

    public String getJobId() {
        return jobId;
    }

    public String getName() {
        return name;
    }

    public String getS3Key() {
        return s3Key;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
