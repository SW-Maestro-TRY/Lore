package com.lore.piecemaker.feedback;

import com.lore.piecemaker.support.PieceMakerIntegrationTest;
import com.lore.piecemaker.support.PieceMakerItSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.net.URI;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 피드백 API 의 통합 검사 — 로그인 없이 보내기, 로그인한 채 보내기, 틀린 입력.
 * 진짜 시큐리티 필터 체인을 지난다 — "이 POST 하나만 로그인 없이 열린다" 는 규칙이 여기서 보인다.
 */
@PieceMakerIntegrationTest
@DisplayName("피드백 API — 보내기(공개 POST)")
class PieceMakerFeedbackApiIT extends PieceMakerItSupport {

    private static final String FEEDBACK = "/api/piece-maker/v1/public/feedback";

    @AfterEach
    void emptyFeedback() {
        jdbc.execute("TRUNCATE TABLE piece_maker_feedback RESTART IDENTITY");
    }

    @Test
    @DisplayName("AC-2-10-1 로그인 없이 오류 신고를 보내면 저장되고 user_id 는 비어 있다")
    void anonymousErrorReportIsSaved() throws Exception {
        MvcResult result = postJson(null, FEEDBACK,
                Map.of("kind", "ERROR_REPORT", "body", "  카드 T2의 회차가 틀린 것 같아요  "));

        assertThat(status(result)).isEqualTo(200);
        var data = data(result);
        assertThat(data.path("kind").asText()).isEqualTo("ERROR_REPORT");
        assertThat(data.path("body").asText()).isEqualTo("카드 T2의 회차가 틀린 것 같아요");
        assertThat(data.path("id").asLong()).isPositive();
        assertThat(data.has("createdAt")).isTrue();
        assertThat(data.has("userId")).as("보낸 사람은 응답에 싣지 않는다").isFalse();

        assertThat(feedbackCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select user_id from piece_maker_feedback", Long.class)).isNull();
    }

    @Test
    @DisplayName("AC-2-10-2 로그인한 채 판정 후기를 보내면 user_id 가 적힌다")
    void loggedInReviewRecordsTheUser() throws Exception {
        Long user = newUserId();

        MvcResult result = postJson(user, FEEDBACK,
                Map.of("kind", "judgement_review", "body", "근거 카드가 제 가설과 맞아서 납득됐어요"));

        assertThat(status(result)).isEqualTo(200);
        assertThat(data(result).path("kind").asText()).as("소문자도 받고 대문자로 저장한다").isEqualTo("JUDGEMENT_REVIEW");
        assertThat(jdbc.queryForObject("select user_id from piece_maker_feedback", Long.class)).isEqualTo(user);
        assertThat(jdbc.queryForObject("select kind from piece_maker_feedback", String.class)).isEqualTo("JUDGEMENT_REVIEW");
    }

    @Test
    @DisplayName("AC-2-10-3 kind 가 둘 중 하나가 아니면 400 INVALID_INPUT")
    void unknownKindIs400() throws Exception {
        MvcResult result = postJson(null, FEEDBACK, Map.of("kind", "PRAISE", "body", "좋아요"));

        assertThat(status(result)).isEqualTo(400);
        assertThat(errorCode(result)).isEqualTo("INVALID_INPUT");
        assertThat(errorMessage(result)).contains("kind");
        assertThat(feedbackCount()).isZero();
    }

    @Test
    @DisplayName("AC-2-10-4 body 가 비었거나 빈칸만 있으면 400 INVALID_INPUT")
    void blankBodyIs400() throws Exception {
        MvcResult missing = postJson(null, FEEDBACK, Map.of("kind", "ERROR_REPORT"));
        MvcResult blank = postJson(null, FEEDBACK, Map.of("kind", "ERROR_REPORT", "body", "   "));

        assertThat(status(missing)).isEqualTo(400);
        assertThat(status(blank)).isEqualTo(400);
        assertThat(errorMessage(blank)).contains("body");
        assertThat(feedbackCount()).isZero();
    }

    @Test
    @DisplayName("AC-2-10-5 body 가 2,000자를 넘으면 400 INVALID_INPUT, 2,000자는 저장된다")
    void bodyLimitIs2000() throws Exception {
        String exactly = "가".repeat(PieceMakerFeedbackService.BODY_MAX_LENGTH);
        String over = "가".repeat(PieceMakerFeedbackService.BODY_MAX_LENGTH + 1);

        assertThat(status(postJson(null, FEEDBACK, Map.of("kind", "ERROR_REPORT", "body", exactly)))).isEqualTo(200);
        MvcResult tooLong = postJson(null, FEEDBACK, Map.of("kind", "ERROR_REPORT", "body", over));
        assertThat(status(tooLong)).isEqualTo(400);
        assertThat(errorMessage(tooLong)).contains("2,000");
        assertThat(feedbackCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-2-10-6 같은 자리의 GET 은 405 다 — 공개는 POST 하나뿐이고 GET 으로는 아무것도 못 본다")
    void getIsNotAReadApi() throws Exception {
        MvcResult result = getAnonymously(FEEDBACK);

        // 서버가 터진 것(500)이 아니라 부른 쪽이 방식을 틀린 것(405)으로 답한다 — 공통 예외 처리기가 맡는다.
        assertThat(status(result)).isEqualTo(405);
        assertThat(errorCode(result)).isEqualTo("METHOD_NOT_ALLOWED");
        assertThat(result.getResponse().getHeader("Allow")).isEqualTo("POST");
        assertThat(result.getResponse().getContentAsString()).doesNotContain("\"kind\"");
    }

    @Test
    @DisplayName("AC-2-10-7 body 에 NUL 문자가 있으면 400 INVALID_INPUT — DB 오류(500)로 가지 않는다")
    void nulCharacterIs400() throws Exception {
        MvcResult result = postJson(null, FEEDBACK, Map.of("kind", "ERROR_REPORT", "body", "앞\0뒤"));

        assertThat(status(result)).isEqualTo(400);
        assertThat(errorCode(result)).isEqualTo("INVALID_INPUT");
        assertThat(errorMessage(result)).contains("body");
        assertThat(feedbackCount()).isZero();
    }

    @Test
    @DisplayName("AC-2-10-8 몸통이 32KiB 를 넘으면 읽지 않고 400 INVALID_INPUT")
    void oversizedBodyIs400() throws Exception {
        // "가" 는 UTF-8 로 3바이트라 위 끝의 세 배쯤 된다. 본문 2,000자 검사까지 가지 않고 필터에서 끊긴다.
        String huge = "가".repeat(PieceMakerFeedbackSizeLimitFilter.MAX_BODY_BYTES);

        MvcResult result = postJson(null, FEEDBACK, Map.of("kind", "ERROR_REPORT", "body", huge));

        assertThat(status(result)).isEqualTo(400);
        assertThat(errorCode(result)).isEqualTo("INVALID_INPUT");
        assertThat(errorMessage(result)).contains("너무 큽니다");
        assertThat(feedbackCount()).isZero();
    }

    @Test
    @DisplayName("AC-2-10-8 주소를 퍼센트 인코딩으로 적어 보내도 몸통의 위 끝은 그대로다")
    void oversizedBodyOnAnEncodedPathIs400() throws Exception {
        // "feedbac%6B" 는 풀면 "feedback" 이다. 시큐리티와 컨트롤러는 푼 경로로 맞추므로 이 주소도 같은 API 에 닿는다.
        URI encoded = URI.create(FEEDBACK.replace("feedback", "feedbac%6B"));
        String small = json.writeValueAsString(Map.of("kind", "ERROR_REPORT", "body", "같은 API 에 닿는다"));
        String huge = json.writeValueAsString(Map.of("kind", "ERROR_REPORT",
                "body", "가".repeat(PieceMakerFeedbackSizeLimitFilter.MAX_BODY_BYTES)));

        MvcResult reached = mockMvc.perform(post(encoded).contentType(MediaType.APPLICATION_JSON).content(small)).andReturn();
        MvcResult tooLarge = mockMvc.perform(post(encoded).contentType(MediaType.APPLICATION_JSON).content(huge)).andReturn();

        assertThat(status(reached)).as("인코딩한 주소도 피드백 API 다").isEqualTo(200);
        assertThat(status(tooLarge)).isEqualTo(400);
        assertThat(errorMessage(tooLarge)).contains("너무 큽니다");
        assertThat(feedbackCount()).isEqualTo(1);
    }

    private long feedbackCount() {
        Long n = jdbc.queryForObject("select count(*) from piece_maker_feedback", Long.class);
        return n == null ? 0 : n;
    }
}
