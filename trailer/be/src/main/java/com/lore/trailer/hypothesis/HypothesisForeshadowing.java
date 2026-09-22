package com.lore.trailer.hypothesis;

import com.lore.trailer.foreshadowing.Foreshadowing;
import com.lore.trailer.foreshadowing.dto.ForeshadowingResponses;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 가설에 담은 카드 한 장의 복사본 — 표 {@code hypothesis_foreshadowing}의 한 줄.
 *
 * <p>{@code foreshadowings} 를 가리키지 않는다. 맡길 때 카드 API(2-4)의 열두 칸을 그 회차 N 으로 가린 값 그대로
 * 복사한다(NA decisions.md 1-19). 카드 표를 갈아 넣어도(다시 만들기, 한국어 채우기) 판정받은 가설의 근거는 그대로다.
 * 검색용 글과 해시 둘은 복사하지 않는다 — 해시는 가설에 있다.
 */
@Entity
@Table(name = "hypothesis_foreshadowing",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_hypothesis_foreshadowing_position", columnNames = {"hypothesis_id", "card_position"}),
                @UniqueConstraint(name = "uk_hypothesis_foreshadowing_card", columnNames = {"hypothesis_id", "thread_id"})})
public class HypothesisForeshadowing {

    private static final String PEOPLE_SEPARATOR = "\n";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hypothesis_id", nullable = false)
    private Hypothesis hypothesis;

    /** 근거의 순서. 0부터. 요청 {@code cards[]} 의 순서다. */
    @Column(name = "card_position", nullable = false)
    private int cardPosition;

    /** 독자의 해석. 없으면 빈 글. */
    @Column(nullable = false, columnDefinition = "text")
    private String note;

    // ── 아래 열둘은 카드 API 의 열둘이다. 회수 칸 셋은 이미 회차 N 으로 가린 값이라 그대로 나간다 ──

    @Column(name = "thread_id", nullable = false, length = 16)
    private String threadId;

    @Column(name = "start_chapter", nullable = false)
    private int startChapter;

    @Column(name = "thread_kind", nullable = false, length = 40)
    private String threadKind;

    @Column(nullable = false, columnDefinition = "text")
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String fact;

    @Column(name = "thread_related_people", nullable = false, columnDefinition = "text")
    private String threadRelatedPeople;

    @Column(nullable = false, columnDefinition = "text")
    private String excerpt;

    @Column(length = 16)
    private String scene;

    @Column(name = "scene_excerpt", nullable = false, columnDefinition = "text")
    private String sceneExcerpt;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "resolved_chapter")
    private Integer resolvedChapter;

    @Column(columnDefinition = "text")
    private String resolution;

    protected HypothesisForeshadowing() {
    }

    /** 카드 표의 한 줄을 N화 독자가 본 값으로 복사한다. 회수 칸 셋은 엔티티가 N 으로 가려 준다. */
    static HypothesisForeshadowing copyOf(Hypothesis owner, Foreshadowing card, int chapter, int position, String note) {
        HypothesisForeshadowing copy = new HypothesisForeshadowing();
        copy.hypothesis = owner;
        copy.cardPosition = position;
        copy.note = note == null ? "" : note;
        copy.threadId = card.getThreadId();
        copy.startChapter = card.getStartChapter();
        copy.threadKind = card.getThreadKind();
        copy.title = card.getTitle();
        copy.fact = card.getFact();
        copy.threadRelatedPeople = String.join(PEOPLE_SEPARATOR, card.people());
        copy.excerpt = card.getExcerpt();
        copy.scene = card.getScene();
        copy.sceneExcerpt = card.getSceneExcerpt();
        copy.status = card.statusAt(chapter);
        copy.resolvedChapter = card.resolvedChapterAt(chapter);
        copy.resolution = card.resolutionAt(chapter);
        return copy;
    }

    /** 복사한 그대로를 카드 API 의 모양으로. 가설 응답(2-6)의 {@code cards[]} 다. */
    public ForeshadowingResponses.Card toCard() {
        return new ForeshadowingResponses.Card(threadId, startChapter, threadKind, title, fact,
                Foreshadowing.splitPeople(threadRelatedPeople), excerpt, scene, sceneExcerpt,
                status, resolvedChapter, resolution);
    }

    public String getThreadId() {
        return threadId;
    }

    public int getCardPosition() {
        return cardPosition;
    }

    public String getNote() {
        return note;
    }
}
