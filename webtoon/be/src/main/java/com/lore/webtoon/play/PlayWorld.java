package com.lore.webtoon.play;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** 사용자가 「세계관 만들기」로 지은 세계 하나. 운영자가 정한 세계는 {@link PlayCatalog} 의 파일에 있다. */
@Entity
@Table(name = "webtoon_play_world")
public class PlayWorld {

    static final String DRAFT = "DRAFT", PUBLISHED = "PUBLISHED";
    static final String IDLE = "IDLE", RUNNING = "RUNNING", ERROR = "ERROR";

    @Id
    @Column(length = 40)
    String id;
    @Column(nullable = false)
    Long ownerId;
    @Column(nullable = false, length = 20)
    String status = DRAFT;
    @Column(nullable = false, length = 20)
    String making = IDLE;
    @Column(length = 300)
    String error;
    /** 화면이 보여 주는 세계 — 이름·장르·소개·규칙·자리·인물·사건. */
    @Column(nullable = false, columnDefinition = "text")
    String docJson = "{}";
    /** 만들 때 넣은 것 — 적은 칸·참고한 설정집 id·요청. 다시 만들기가 이것으로 다시 돈다. */
    @Column(nullable = false, columnDefinition = "text")
    String inputJson = "{}";
    /** 참고한 설정집 사본. 화면에는 내주지 않는다. */
    @Column(nullable = false, columnDefinition = "text")
    String booksJson = "[]";
    @Column(nullable = false)
    Instant createdAt;
    @Column(nullable = false)
    Instant updatedAt;
    Instant madeAt;
    Instant publishedAt;

    protected PlayWorld() {
    }

    PlayWorld(String id, Long ownerId, Instant at) {
        this.id = id;
        this.ownerId = ownerId;
        this.createdAt = at;
        this.updatedAt = at;
    }

    boolean published() {
        return PUBLISHED.equals(status);
    }
}
