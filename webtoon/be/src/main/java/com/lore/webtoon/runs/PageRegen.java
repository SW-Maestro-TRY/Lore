package com.lore.webtoon.runs;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 한 장을 <b>다시 그리는</b> 작업 하나 — 편집실에서 누른다.
 *
 * <h2>왜 {@code WebtoonJob} 이 아니라 따로 두나</h2>
 *
 * 만들기 작업은 이야기·시트·페이지를 순서대로 지나는 <b>단계가 있는</b>
 * 흐름이다. 다시 그리기는 이미 다 만든 작품에서 <b>장 하나만</b> 다시
 * 부르는 것이라 단계가 없다 — 상태도 {@code QUEUED → RUNNING → DONE/ERROR}
 * 넷뿐이다. WebtoonJob 에 끼워 넣으면 그 넷을 위해 stage·pick·checkpoints
 * 같은 칸을 전부 null 로 둬야 한다.
 *
 * <b>id 는 사람이 못 짐작해야 한다.</b> 진행 화면이 이 번호로 상태를 묻는데,
 * 순번(1, 2, 3…)이면 남의 다시 그리기가 지금 뭘 하는지도 보인다.
 */
@Entity
@Table(name = "webtoon_page_regen")
public class PageRegen {

    @Id
    @Column(length = 32)
    private String id;

    @Column(name = "run_id", nullable = false, length = 120)
    private String runId;

    @Column(name = "page_no", nullable = false)
    private int pageNo;

    /** 편집실에서 사람이 적은 한 마디. 하네스가 그리는 프롬프트 뒤에 붙인다. */
    @Column(length = 500)
    private String note;

    @Column(nullable = false, length = 20)
    private String status;

    /** 실패했을 때 사람에게 보여 줄 한 줄. */
    @Column(length = 300)
    private String error;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PageRegen() {
    }

    static PageRegen queued(String id, String runId, int pageNo, String note, Instant at) {
        PageRegen one = new PageRegen();
        one.id = id;
        one.runId = runId;
        one.pageNo = pageNo;
        one.note = note;
        one.status = RegenStatus.QUEUED.wire();
        one.createdAt = at;
        one.updatedAt = at;
        return one;
    }

    void move(RegenStatus to, Instant at) {
        this.status = to.wire();
        this.updatedAt = at;
    }

    void fail(String reason, Instant at) {
        this.status = RegenStatus.ERROR.wire();
        this.error = reason == null ? null : reason.substring(0, Math.min(300, reason.length()));
        this.updatedAt = at;
    }

    public String getId() {
        return id;
    }

    public String getRunId() {
        return runId;
    }

    public int getPageNo() {
        return pageNo;
    }

    public String getNote() {
        return note;
    }

    public String getStatus() {
        return status;
    }

    public String getError() {
        return error;
    }
}
