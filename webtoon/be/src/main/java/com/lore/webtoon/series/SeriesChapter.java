package com.lore.webtoon.series;

import jakarta.persistence.*;

/** 생성 시 작가가 확인한 기억의 불변 사본. 실패한 시도도 보존한다. */
@Entity
@Table(name = "webtoon_series_chapter", indexes = @Index(name = "idx_series_root", columnList = "root_job"),
       uniqueConstraints = @UniqueConstraint(name = "uq_series_request", columnNames = {"root_job", "request_token"}))
public class SeriesChapter {
    @Id @Column(name = "job_id", length = 40) String jobId;
    @Column(name = "root_job", nullable = false, length = 40) String rootJob;
    @Column(name = "source_job", nullable = false, length = 40) String sourceJob;
    @Column(name = "episode_number", nullable = false) int episodeNumber;
    @Column(name = "request_token", nullable = false, length = 64) String requestToken;
    @Column(name = "memory_json", nullable = false, columnDefinition = "text") String memoryJson;
    @Column(name="owner_id", nullable=false) Long ownerId;
    protected SeriesChapter() {}
    SeriesChapter(String jobId, String rootJob, String sourceJob, int episodeNumber, String requestToken, String memoryJson) {
        this.jobId=jobId; this.rootJob=rootJob; this.sourceJob=sourceJob;
        this.episodeNumber=episodeNumber; this.requestToken=requestToken; this.memoryJson=memoryJson;
    }

    public String jobId() { return jobId; }
    public String rootJob() { return rootJob; }
    public int episodeNumber() { return episodeNumber; }
}
