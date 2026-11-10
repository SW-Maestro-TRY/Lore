package com.lore.webtoon.series;

import jakarta.persistence.*;
import java.time.Instant;

/** 앞 화를 읽고 정리한 연재 설정집. 앞 화 하나에 하나이며, 다음 화 창의 칸을 채운다. */
@Entity
@Table(name = "webtoon_series_digest")
public class SeriesDigest {
    @Id @Column(name = "source_job", length = 40) String sourceJob;
    @Column(name = "owner_id", nullable = false) Long ownerId;
    @Column(nullable = false, length = 20) String status = "RUNNING";
    @Column(name = "result_json", columnDefinition = "text") String resultJson;
    @Column(length = 300) String error;
    @Column(name = "created_at", nullable = false) Instant createdAt;
    @Column(name = "updated_at", nullable = false) Instant updatedAt;
    protected SeriesDigest() {}
    SeriesDigest(String sourceJob, Long ownerId) {
        this.sourceJob = sourceJob; this.ownerId = ownerId;
        this.createdAt = Instant.now(); this.updatedAt = createdAt;
    }
}
