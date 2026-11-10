package com.lore.piecemaker.resultview;

import com.lore.piecemaker.support.PieceMakerIntegrationTest;
import com.lore.piecemaker.support.PieceMakerItSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@PieceMakerIntegrationTest
@TestPropertySource(properties = "app.analytics.enabled=false")
class ResultViewDisabledApiIT extends PieceMakerItSupport {
    @Test
    @DisplayName("AC-2-11-8 설정이 꺼지면 정상 결과도 HTTP409이며 DB 기록과 성공 시각이 없다")
    void disabledDoesNotPersist() throws Exception {
        long user = newUserId();
        long hypothesis = jdbc.queryForObject("""
                insert into hypotheses (user_id, chapter, title, claim, state_digest, cards_digest,
                    judgement_status, judgement, judged_at)
                values (?, 400, '시험 가설', '시험 주장', ?, ?, 'COMPLETE',
                    '{"grade":"likely","reason":"근거가 있습니다","support":[],"against":[]}'::jsonb, now())
                returning id
                """, Long.class, user, "a".repeat(64), "b".repeat(64));
        var result = mockMvc.perform(post(path(hypothesis)).with(asUser(user))).andReturn();
        assertThat(status(result)).isEqualTo(409);
        assertThat(errorCode(result)).isEqualTo("PIECE_MAKER_RESULT_VIEW_DISABLED");
        assertThat(errorMessage(result)).isEqualTo("정상 판정 열람 기록이 비활성화되어 있습니다");
        assertThat(body(result).path("success").asBoolean()).isFalse();
        assertThat(body(result).path("data").isNull()).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from piece_maker_first_result_view where user_id = ?", Long.class, user)).isZero();
    }

    @Test
    @DisplayName("AC-2-11-8 기록이 꺼져 있어도 로그인 없는 요청은 먼저 HTTP401이다")
    void disabledStillRequiresAuthentication() throws Exception {
        var result = mockMvc.perform(post(path(1))).andReturn();
        assertThat(status(result)).isEqualTo(401);
    }

    private static String path(long id) {
        return "/api/piece-maker/v1/hypotheses/" + id + "/result-view";
    }
}
