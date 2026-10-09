package com.lore.zzal.chat.session;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * 대화의 한 마디 — 펫 턴 또는 사용자 턴(#704).
 *
 * ★ 펫 턴에는 무엇이 만들었나(생성기·폴백 사유)와 무엇을 물었나(질문 항목)가 남는다. 사용자 턴의 질문 항목은
 *   "바로 앞 펫 턴이 물은 것" 을 그대로 옮겨 적은 것이다 — 그 항목에 답이 있다는 표시다.
 */
@Entity
@Table(name = "zzal_chat_turn",
        uniqueConstraints = @UniqueConstraint(name = "uk_zzal_chat_turn_session_idx", columnNames = {"session_id", "idx"}),
        indexes = @Index(name = "idx_zzal_chat_turn_pet", columnList = "pet_id"))
public class ZzalChatTurn {

    /** 펫 대사 칸 길이. 60자 규칙이라 넉넉하다. */
    public static final int LINE_MAX = 160;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "pet_id", nullable = false)
    private Long petId;

    @Column(nullable = false)
    private int idx;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 4)
    private Speaker speaker;

    @Enumerated(EnumType.STRING)
    @Column(name = "turn_type", length = 12)
    private TurnType turnType;

    @Column(nullable = false, length = LINE_MAX)
    private String line;

    @Column(length = 20)
    private String motion;

    @Column(length = 10)
    private String generator;

    @Column(name = "filtered_reason", length = 32)
    private String filteredReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "question_item", length = 10)
    private QuestionItem questionItem;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ZzalChatTurn() {
    }

    public static ZzalChatTurn pet(ZzalChatSession s, int idx, TurnType type, String line, String motion,
                                   String generator, String filteredReason, QuestionItem item, Instant at) {
        ZzalChatTurn t = base(s, idx, Speaker.PET, line, at);
        t.turnType = type;
        t.motion = motion;
        t.generator = generator;
        t.filteredReason = filteredReason == null ? null
                : (filteredReason.length() > 32 ? filteredReason.substring(0, 32) : filteredReason);
        t.questionItem = item;
        return t;
    }

    public static ZzalChatTurn user(ZzalChatSession s, int idx, String line, QuestionItem answering, Instant at) {
        ZzalChatTurn t = base(s, idx, Speaker.USER, line, at);
        t.questionItem = answering;
        return t;
    }

    private static ZzalChatTurn base(ZzalChatSession s, int idx, Speaker speaker, String line, Instant at) {
        ZzalChatTurn t = new ZzalChatTurn();
        t.sessionId = s.getId();
        t.petId = s.getPetId();
        t.idx = idx;
        t.speaker = speaker;
        String l = line == null ? "" : line;
        t.line = l.length() > LINE_MAX ? l.substring(0, LINE_MAX) : l;
        t.createdAt = at;
        return t;
    }

    public Long getId() {
        return id;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public Long getPetId() {
        return petId;
    }

    public int getIdx() {
        return idx;
    }

    public Speaker getSpeaker() {
        return speaker;
    }

    public TurnType getTurnType() {
        return turnType;
    }

    public String getLine() {
        return line;
    }

    public String getMotion() {
        return motion;
    }

    public String getGenerator() {
        return generator;
    }

    public String getFilteredReason() {
        return filteredReason;
    }

    public QuestionItem getQuestionItem() {
        return questionItem;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public boolean isPet() {
        return speaker == Speaker.PET;
    }
}
