package com.lore.webtoon.feedback;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 설문 답 한 번(#471). 표를 따로 두는 이유는 {@code V20260927_1927__webtoon_feedback.sql}
 * 머리에 있다. 값은 전부 {@link WebtoonFeedbackService} 가 거른 뒤라, 여기는 옮겨 담기만 한다.
 */
@Entity
@Table(name = "webtoon_feedback")
public class WebtoonFeedback {

    /** 완성 직후의 짧은 설문 · 마이페이지의 전체 설문. */
    public enum Kind { SHORT, FULL }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 10)
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    private Kind kind;

    @Column(name = "run_id", length = 64)
    private String runId;

    @Column(name = "user_id")
    private Long userId;

    @Column(length = 64)
    private String uid;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String answers;

    @Column(columnDefinition = "TEXT")
    private String comment;

    @Column(name = "wants_interview", nullable = false)
    private boolean wantsInterview;

    @Column(length = 200)
    private String contact;

    @Column(nullable = false)
    private int rewarded;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected WebtoonFeedback() {
    }

    static WebtoonFeedback of(Kind kind, String runId, Long userId, String uid, String answers,
                              String comment, boolean wantsInterview, String contact, Instant at) {
        WebtoonFeedback f = new WebtoonFeedback();
        f.kind = kind;
        f.runId = runId;
        f.userId = userId;
        f.uid = uid;
        f.answers = answers;
        f.comment = comment;
        f.wantsInterview = wantsInterview;
        f.contact = contact;
        f.createdAt = at;
        return f;
    }

    void rewarded(int amount) {
        this.rewarded = amount;
    }

    public Long getId() { return id; }
    public Kind getKind() { return kind; }
    public String getRunId() { return runId; }
    public Long getUserId() { return userId; }
    public String getAnswers() { return answers; }
    public String getComment() { return comment; }
    public boolean isWantsInterview() { return wantsInterview; }
    public String getContact() { return contact; }
    public int getRewarded() { return rewarded; }
    public Instant getCreatedAt() { return createdAt; }
}
