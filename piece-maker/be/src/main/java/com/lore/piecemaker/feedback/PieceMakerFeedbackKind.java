package com.lore.piecemaker.feedback;

import java.util.Optional;

/** 피드백의 종류 둘. 표 {@code piece_maker_feedback.kind} 에 이 이름 그대로 들어간다. */
public enum PieceMakerFeedbackKind {

    /** 오류 신고 — 카드가 틀렸다, 화면이 깨졌다 같은 "고쳐 달라"는 제보. */
    ERROR_REPORT,

    /** 판정 후기 — 받은 판정이 납득되는지, 어땠는지에 대한 의견. */
    JUDGEMENT_REVIEW;

    /** 요청 몸통의 글을 종류로 바꾼다. 모르는 글이면 비어 있다 — 호출한 쪽이 400 과 한국어 문구를 만든다. */
    public static Optional<PieceMakerFeedbackKind> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        for (PieceMakerFeedbackKind kind : values()) {
            if (kind.name().equalsIgnoreCase(raw.strip())) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }
}
