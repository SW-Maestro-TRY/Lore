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
 * ★ 펫 턴에는 무엇이 만들었나(생성기·첫 실패 사유·결과 {@code outcome}·걸린 시간)와 무엇을 물었나(질문 항목)가 남는다.
 *   사용자 턴의 질문 항목은 "바로 앞 펫 턴이 물은 것" 을 그대로 옮겨 적은 것이다 — 그 항목에 답이 있다는 표시다.
 * ★ 사용자 턴에는 그 말에서 읽은 것(#709)이 남는다 — 호칭(코드 패턴·모델 각각, 둘이 다르면 둘 다 보인다)·
 *   이번 질문 항목에 대한 답 요지(모델)·되물음(코드 정규식 OR 모델). 요지는 v2 기억의 재료다.
 */
@Entity
@Table(name = "zzal_chat_turn",
        uniqueConstraints = @UniqueConstraint(name = "uk_zzal_chat_turn_session_idx", columnNames = {"session_id", "idx"}),
        indexes = @Index(name = "idx_zzal_chat_turn_pet", columnList = "pet_id"))
public class ZzalChatTurn {

    /** 대사 칸 길이(글자). 넘으면 잘라 저장한다({@link #fit}). */
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

    /** 펫 턴 — ok · retried_ok · failed_closed · truncated (#709). */
    @Column(length = 14)
    private String outcome;

    /** 펫 턴 — 대사를 받는 데 걸린 시간(재호출까지 합). */
    @Column(name = "latency_ms")
    private Integer latencyMs;

    /** 사용자 턴 — 코드 패턴({@link CallMeExtractor})이 뽑은 호칭. */
    @Column(name = "call_me_code", length = 20)
    private String callMeCode;

    /** 사용자 턴 — 모델이 읽은 호칭. */
    @Column(name = "call_me_model", length = 20)
    private String callMeModel;

    /** 사용자 턴 — 이번 질문 항목에 대한 답의 요지(모델). */
    @Column(name = "user_said", length = LINE_MAX)
    private String userSaid;

    /** 사용자 턴 — 되물었나(코드 정규식 OR 모델). */
    @Column(name = "asked_back")
    private Boolean askedBack;

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

    /** 펫 턴 + 결과·걸린 시간(#709). */
    public static ZzalChatTurn pet(ZzalChatSession s, int idx, TurnType type, String line, String motion,
                                   String generator, String filteredReason, QuestionItem item, String outcome,
                                   long latencyMs, Instant at) {
        ZzalChatTurn t = pet(s, idx, type, line, motion, generator, filteredReason, item, at);
        t.outcome = outcome;
        t.latencyMs = (int) Math.min(Integer.MAX_VALUE, Math.max(0, latencyMs));
        return t;
    }

    /** 사용자 턴에 그 말에서 읽은 것을 적는다(펫 대사를 받은 뒤). */
    public void recordRead(String callMeCode, String callMeModel, String userSaid, boolean askedBack) {
        this.callMeCode = cut(callMeCode, 20);
        this.callMeModel = cut(callMeModel, 20);
        this.userSaid = userSaid == null ? null : fit(userSaid);
        this.askedBack = askedBack;
    }

    /** 칸 길이(글자 = 코드포인트)에 맞춰 자른다. */
    public static String fit(String line) {
        String l = line == null ? "" : line;
        if (l.codePointCount(0, l.length()) <= LINE_MAX) {
            return l;
        }
        return l.substring(0, l.offsetByCodePoints(0, LINE_MAX));
    }

    private static String cut(String v, int max) {
        return v == null ? null : (v.length() > max ? v.substring(0, max) : v);
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
        t.line = fit(line);
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

    public String getOutcome() {
        return outcome;
    }

    public Integer getLatencyMs() {
        return latencyMs;
    }

    public String getCallMeCode() {
        return callMeCode;
    }

    public String getCallMeModel() {
        return callMeModel;
    }

    public String getUserSaid() {
        return userSaid;
    }

    public Boolean getAskedBack() {
        return askedBack;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public boolean isPet() {
        return speaker == Speaker.PET;
    }
}
