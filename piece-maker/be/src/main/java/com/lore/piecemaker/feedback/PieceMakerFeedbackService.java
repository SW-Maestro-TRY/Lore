package com.lore.piecemaker.feedback;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.piecemaker.feedback.dto.PieceMakerFeedbackRequests;
import com.lore.piecemaker.feedback.dto.PieceMakerFeedbackResponses;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 피드백 — 종류와 본문을 검사해 저장한다.
 *
 * <p>검사는 모두 여기서 한다. 틀린 칸마다 한국어 문구를 붙여 400 {@code INVALID_INPUT} 으로 답한다.
 * 종류를 enum 이 아니라 글로 받는 까닭도 그것이다 — Jackson 이 먼저 거절하면 "kind 가 틀렸다" 는 말을 할 수 없다.
 */
@Service
public class PieceMakerFeedbackService {

    /** 본문의 위 끝. 가설의 해석(4,000자)보다 짧게 둔다 — 피드백은 한두 단락이다. */
    static final int BODY_MAX_LENGTH = 2_000;

    private final PieceMakerFeedbackRepository repository;

    public PieceMakerFeedbackService(PieceMakerFeedbackRepository repository) {
        this.repository = repository;
    }

    /** 피드백을 저장한다. {@code userId} 는 로그인했을 때만 있고, 없으면 익명으로 남는다. */
    @Transactional
    public PieceMakerFeedbackResponses.Feedback create(Long userId, PieceMakerFeedbackRequests.Create request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "요청 몸통이 필요합니다");
        }
        PieceMakerFeedbackKind kind = PieceMakerFeedbackKind.parse(request.kind())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_INPUT,
                        "kind는 ERROR_REPORT(오류 신고) 또는 JUDGEMENT_REVIEW(판정 후기) 입니다"));
        String body = request.body() == null ? "" : request.body().strip();
        if (body.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "body가 비었습니다");
        }
        // PostgreSQL 의 text 는 NUL(U+0000)을 담지 못한다. 여기서 거르지 않으면 저장할 때 DB 가 거절해 500 이 된다.
        if (body.indexOf('\0') >= 0) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "body에 저장할 수 없는 문자(NUL)가 있습니다");
        }
        if (body.length() > BODY_MAX_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "body는 %,d자까지입니다(지금 %,d자)".formatted(BODY_MAX_LENGTH, body.length()));
        }
        PieceMakerFeedback saved = repository.save(PieceMakerFeedback.of(kind, body, userId, Instant.now()));
        return PieceMakerFeedbackResponses.Feedback.from(saved);
    }
}
