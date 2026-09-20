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
 * 편집실에서 그림 위에 얹은 것 — 말풍선 · 스티커 · 효과음.
 *
 * <h2>왜 DB 로 가져오는가</h2>
 *
 * 지금까지 이것은 작품 폴더의 {@code overlay.json} 하나였다. 그래서 그 폴더가
 * 없어지면 <b>사람이 직접 얹은 것이 통째로 사라진다</b> — 그림은 다시 그릴 수
 * 있지만 말풍선을 어디에 어떻게 놓았는지는 그 사람만 안다. 만들어진 것 중에
 * 가장 잃으면 안 되는 값이다.
 *
 * 무엇보다 <b>그 폴더가 서버에는 없다.</b> 편집실이 서버에서 안 돌던 이유가
 * 그것이다.
 *
 * <h2>모양 그대로 담는다</h2>
 *
 * 화면이 보내고 굽는 쪽이 읽는 모양({@code {"scenes": …, "gaps": …}})을 글자
 * 그대로 담는다. 칸으로 쪼개면 얹을 것이 한 종류 늘 때마다 표를 고쳐야 하고,
 * 세 곳(화면 · 여기 · 굽는 쪽)이 같은 모양을 알고 있어야 한다. 대신 담기
 * <b>전에</b> 값을 깎는다({@link OverlayStore}) — 브라우저에서 온 값이다.
 */
@Entity
@Table(name = "webtoon_overlay",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_webtoon_overlay", columnNames = {"run_id", "episode"}))
public class WebtoonOverlay {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false, length = 120)
    private String runId;

    /** 몇 화인가. 지금은 늘 1 이다 — 이어그리기가 붙으면 여기가 늘어난다. */
    @Column(name = "episode", nullable = false)
    private int episode;

    /** {@code {"scenes": …, "gaps": …}} 그대로. 깎아서 담는다. */
    @Column(name = "data_json", nullable = false, columnDefinition = "text")
    private String dataJson;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WebtoonOverlay() {
    }

    static WebtoonOverlay of(String runId, int episode, String dataJson, Instant at) {
        WebtoonOverlay one = new WebtoonOverlay();
        one.runId = runId;
        one.episode = episode;
        one.dataJson = dataJson;
        one.updatedAt = at;
        return one;
    }

    void update(String dataJson, Instant at) {
        this.dataJson = dataJson;
        this.updatedAt = at;
    }

    public String getRunId() {
        return runId;
    }

    public String getDataJson() {
        return dataJson;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
