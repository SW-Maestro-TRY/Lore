package com.lore.piecemaker.feedback;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 독자가 보낸 피드백 한 건 — 표 {@code piece_maker_feedback} 의 한 줄(V20261001_1843__piece_maker_feedback.sql).
 *
 * <p>종류({@link PieceMakerFeedbackKind})와 본문만 받는다. 로그인 없이도 보낼 수 있어 {@code userId} 는 비어 있을 수 있다.
 * 보낸 뒤에는 고치지 않는다 — 그래서 바꾸는 메서드가 없다.
 */
@Entity
@Table(name = "piece_maker_feedback")
public class PieceMakerFeedback {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PieceMakerFeedbackKind kind;

    @Column(nullable = false, columnDefinition = "text")
    private String body;

    /** 보낸 독자. {@code users.id}. 로그인하지 않았으면 null. 엔티티로 잇지 않는다 — hypotheses 와 같은 방식이다. */
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PieceMakerFeedback() {
    }

    private PieceMakerFeedback(PieceMakerFeedbackKind kind, String body, Long userId, Instant now) {
        this.kind = kind;
        this.body = body;
        this.userId = userId;
        this.createdAt = now;
    }

    /** 독자가 보낸 피드백. {@code userId} 는 로그인했을 때만 있다. */
    public static PieceMakerFeedback of(PieceMakerFeedbackKind kind, String body, Long userId, Instant now) {
        return new PieceMakerFeedback(kind, body, userId, now);
    }

    public Long getId() {
        return id;
    }

    public PieceMakerFeedbackKind getKind() {
        return kind;
    }

    public String getBody() {
        return body;
    }

    public Long getUserId() {
        return userId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
