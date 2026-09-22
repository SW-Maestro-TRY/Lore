package com.lore.trailer.hypothesis;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 독자가 맡긴 가설 한 건 — 표 {@code hypotheses}의 한 줄(V20260922_1935__trailer_hypotheses.sql).
 *
 * <p>저장이 곧 맡기기다(NA decisions.md 1-20). 만들어지면 {@link #PENDING} 이고, 운영자가 {@code judge.py} 를 돌려
 * 판정을 넣으면 {@link #COMPLETE} 또는 {@link #FAILED} 가 된다. 서버는 판정하지 않는다(later.md 1-2).
 * 맡긴 뒤에는 제목 · 주장 · 카드 · 해석을 고칠 수 없다(1-23) — 그래서 바꾸는 메서드가 판정 둘뿐이다.
 *
 * <p>{@code judgement} 와 {@code presentation} 은 jsonb 칼럼이고 여기서는 JSON 글(String)로 든다.
 * Hibernate 는 String + {@code SqlTypes.JSON} 을 "이미 JSON 인 글"로 보고 직렬화기 없이 그대로 넣고 꺼낸다.
 * 서버는 이 안을 읽지 않는다 — judge.py 출력을 통째로 받아 통째로 준다(1-29 의 {@code cited_cards} 포함).
 */
@Entity
@Table(name = "hypotheses")
public class Hypothesis {

    public static final String PENDING = "PENDING";
    public static final String COMPLETE = "COMPLETE";
    public static final String FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 맡긴 독자. {@code users.id}. 엔티티로 잇지 않는다 — zzal 의 표들과 같은 방식이다. */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 독자가 읽었다고 고른 회차 N. judge.py 가 이 회차로 장부를 자른다. */
    @Column(nullable = false)
    private int chapter;

    @Column(nullable = false, length = 180)
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String claim;

    /** 맡길 때 카드 표에 있던 해시 둘. judge.py 가 자기 파일과 같은 자료인지 이 값으로 본다. */
    @Column(name = "state_digest", nullable = false, length = 64)
    private String stateDigest;

    @Column(name = "cards_digest", nullable = false, length = 64)
    private String cardsDigest;

    @Column(name = "judgement_status", nullable = false, length = 16)
    private String judgementStatus;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String judgement;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String presentation;

    @Column(name = "failure_message", columnDefinition = "text")
    private String failureMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "judged_at")
    private Instant judgedAt;

    /** 담은 카드의 복사본. 순서가 근거의 순서다. 가설과 함께 저장되고 함께 지워진다. */
    @OneToMany(mappedBy = "hypothesis", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("cardPosition ASC")
    private List<HypothesisForeshadowing> cards = new ArrayList<>();

    protected Hypothesis() {
    }

    private Hypothesis(Long userId, int chapter, String title, String claim,
                       String stateDigest, String cardsDigest, Instant now) {
        this.userId = userId;
        this.chapter = chapter;
        this.title = title;
        this.claim = claim;
        this.stateDigest = stateDigest;
        this.cardsDigest = cardsDigest;
        this.judgementStatus = PENDING;
        this.createdAt = now;
    }

    /** 독자가 맡긴 가설. 카드는 {@link #addCard} 로 순서대로 붙인다. */
    public static Hypothesis submit(Long userId, int chapter, String title, String claim,
                                    String stateDigest, String cardsDigest, Instant now) {
        return new Hypothesis(userId, chapter, title, claim, stateDigest, cardsDigest, now);
    }

    void addCard(HypothesisForeshadowing card) {
        cards.add(card);
    }

    /** 운영자가 판정을 넣었다(2-9). 다시 돌린 결과로 덮어쓸 수 있다 — 상태가 무엇이었든 COMPLETE 가 된다. */
    public void complete(String judgementJson, String presentationJson, Instant at) {
        this.judgementStatus = COMPLETE;
        this.judgement = judgementJson;
        this.presentation = presentationJson;
        this.failureMessage = null;
        this.judgedAt = at;
    }

    /** 운영자가 실패를 넣었다(2-9). 독자에게 보일 문구만 남기고 판정 칸은 비운다. */
    public void fail(String message, Instant at) {
        this.judgementStatus = FAILED;
        this.judgement = null;
        this.presentation = null;
        this.failureMessage = message;
        this.judgedAt = at;
    }

    public boolean isPending() {
        return PENDING.equals(judgementStatus);
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public int getChapter() {
        return chapter;
    }

    public String getTitle() {
        return title;
    }

    public String getClaim() {
        return claim;
    }

    public String getStateDigest() {
        return stateDigest;
    }

    public String getCardsDigest() {
        return cardsDigest;
    }

    public String getJudgementStatus() {
        return judgementStatus;
    }

    /** JSON 글. 없으면 null. */
    public String getJudgement() {
        return judgement;
    }

    /** JSON 글. 없으면 null. */
    public String getPresentation() {
        return presentation;
    }

    public String getFailureMessage() {
        return failureMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getJudgedAt() {
        return judgedAt;
    }

    public List<HypothesisForeshadowing> getCards() {
        return Collections.unmodifiableList(cards);
    }
}
