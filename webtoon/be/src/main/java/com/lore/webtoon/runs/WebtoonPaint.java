package com.lore.webtoon.runs;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * 편집실에서 한 장 위에 <b>직접 그린 것</b> — 붓 선과 지우개 선.
 *
 * 말풍선({@link WebtoonOverlay})과 따로 담는다. 둘 다 자동 저장으로 통째로 덮어쓰는데, 한 줄을 같이
 * 쓰면 거의 같은 때 온 두 저장이 서로의 것을 지운다. 장마다 한 줄이라 한 장을 그릴 때 그 장만 오간다.
 *
 * 원본 그림은 안 건드린다 — 굽기({@link BakeService})가 그림 위 · 말풍선 아래에 합친다.
 */
@Entity
@Table(name = "webtoon_overlay_paint",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_webtoon_overlay_paint", columnNames = {"run_id", "episode", "page_no"}))
public class WebtoonPaint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false, length = 120)
    private String runId;

    @Column(name = "episode", nullable = false)
    private int episode;

    @Column(name = "page_no", nullable = false)
    private int pageNo;

    /** {@code {"v":1,"strokes":[…]}} 그대로. 깎아서 담는다({@link PaintStore}). */
    @Column(name = "data_json", nullable = false, columnDefinition = "text")
    private String dataJson;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WebtoonPaint() {
    }

    static WebtoonPaint of(String runId, int episode, int pageNo, String dataJson, Instant at) {
        WebtoonPaint one = new WebtoonPaint();
        one.runId = runId;
        one.episode = episode;
        one.pageNo = pageNo;
        one.dataJson = dataJson;
        one.updatedAt = at;
        return one;
    }

    void update(String dataJson, Instant at) {
        this.dataJson = dataJson;
        this.updatedAt = at;
    }

    public int getPageNo() {
        return pageNo;
    }

    public String getDataJson() {
        return dataJson;
    }
}
