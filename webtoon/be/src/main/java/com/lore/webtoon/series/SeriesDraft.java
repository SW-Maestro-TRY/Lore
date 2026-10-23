package com.lore.webtoon.series;

import jakarta.persistence.*;

/** 다음 화에 넘길 두 층의 기억. 생성 전 초안이며 과거 회차를 바꾸지 않는다. */
@Entity
@Table(name = "webtoon_series_draft")
public class SeriesDraft {
    @Id @Column(length = 40) String sourceJob;
    @Column(nullable = false, columnDefinition = "text") String fixedSettings = "";
    @Column(nullable = false, columnDefinition = "text") String progressState = "";
    @Column(nullable = false, columnDefinition = "text") String nextStory = "";
    @Column(nullable = false) long revision;
    @Column(name="owner_id", nullable=false) Long ownerId;
    protected SeriesDraft() {}
    SeriesDraft(String sourceJob) { this.sourceJob = sourceJob; }
}
