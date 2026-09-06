package com.lore.webtoon.story;

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
 * 지어진 이야기 하나 — 후보 넷 중 한 줄.
 *
 * <h2>왜 DB 로 옮기나</h2>
 *
 * 지금 이야기는 <b>하네스 폴더의 파일</b>에만 있다({@code directions.json} ·
 * {@code pick.json}). 그래서 그 폴더가 없으면 제품이 제목도 줄거리도 못 읽는다 —
 * 마이페이지에 작품이 뜨는데 이름이 없고, 완성본을 열어도 무슨 이야기인지
 * 못 보여준다. 실서버가 정확히 그 상태였다.
 *
 * <h2>하네스 폴더는 작업대지 창고가 아니다</h2>
 *
 * 하네스는 자기 일을 하는 동안 그 폴더를 쓴다(이어서 돌 때 중간 결과를 읽는다).
 * 그건 그대로 둔다. 다만 <b>다 끝난 뒤에 제품이 읽어야 하는 것</b>은 여기로
 * 옮긴다 — 이야기(이 표) · 그림(S3 + {@code webtoon_page}) · 누구 것인가
 * ({@code webtoon_work}). 그러고 나면 그 폴더는 지워도 된다.
 *
 * <h2>후보를 다 남긴다</h2>
 *
 * 고른 것만 남기지 않는다. 무엇 중에서 골랐는지가 있어야 "왜 이 이야기가
 * 됐는지" 를 나중에 볼 수 있고, 고르는 화면을 다시 열 수도 있다. 고른 것은
 * {@link #chosen} 으로 표시한다.
 */
@Entity
@Table(
        name = "webtoon_story",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_webtoon_story_one", columnNames = {"run_id", "n"}),
        indexes = @Index(name = "idx_webtoon_story_run", columnList = "run_id"))
public class WebtoonStory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false, length = 64)
    private String runId;

    /** 후보 번호(1부터). 사람이 고를 때 누르는 번호와 같다. */
    @Column(nullable = false)
    private int n;

    @Column(length = 200)
    private String title;

    @Column(length = 60)
    private String genre;

    /** 줄거리 한 문단. 완성본 머리에 그대로 나간다. */
    /* @Lob 을 안 붙인다. Postgres 에서 `@Lob + String` 은 text 가 아니라
       **큰 객체(oid)** 로 잡혀서, 읽을 때 "Bad value for type long" 이 난다
       (columnDefinition 을 text 로 줘도 그렇다 — 실제로 그랬다).
       긴 글은 그냥 text 로 두면 된다. */
    @Column(columnDefinition = "text")
    private String plot;

    /**
     * 장면 목록(JSON 배열).
     *
     * 그림 한 장이 장면 하나다 — 완성본에서 그림을 누르면 뜨는 설명이 여기서
     * 나온다. 칸으로 쪼개지 않고 통째로 두는 이유: 개수가 이야기마다 다르고,
     * 제품이 하는 일은 <b>순서대로 보여주는 것</b>뿐이라 쪼개 봐야 쓸 데가 없다.
     */
    @Column(name = "scenes_json", columnDefinition = "text")
    private String scenesJson;

    /** 등장인물(JSON 배열). 다시 그릴 때 생김새를 맞추는 데 쓴다. */
    @Column(name = "cast_json", columnDefinition = "text")
    private String castJson;

    /** 사람이 이걸 골랐나. 넷 중 하나만 참이다. */
    @Column(nullable = false)
    private boolean chosen;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected WebtoonStory() {
    }

    private WebtoonStory(String runId, int n, String title, String genre, String plot,
                         String scenesJson, String castJson, Instant createdAt) {
        this.runId = runId;
        this.n = n;
        this.title = title;
        this.genre = genre;
        this.plot = plot;
        this.scenesJson = scenesJson;
        this.castJson = castJson;
        this.chosen = false;
        this.createdAt = createdAt;
    }

    public static WebtoonStory of(String runId, int n, String title, String genre, String plot,
                                  String scenesJson, String castJson, Instant at) {
        return new WebtoonStory(runId, n, title, genre, plot, scenesJson, castJson, at);
    }

    void choose(boolean value) {
        this.chosen = value;
    }

    public Long getId() {
        return id;
    }

    public String getRunId() {
        return runId;
    }

    public int getN() {
        return n;
    }

    public String getTitle() {
        return title;
    }

    public String getGenre() {
        return genre;
    }

    public String getPlot() {
        return plot;
    }

    public String getScenesJson() {
        return scenesJson;
    }

    public String getCastJson() {
        return castJson;
    }

    public boolean isChosen() {
        return chosen;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
