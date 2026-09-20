package com.lore.zzal.chat;

import com.lore.zzal.pet.Personality;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 원망 문장 금지 — 출력 단계 강제(정본 0장 6). 템플릿 전부가 필터를 지나는지도 여기서 고정한다. */
@DisplayName("원망 필터")
class BanFilterTest {

    @Test
    @DisplayName("★ 원망·비난·죄책감 문장은 안전한 한 줄로 바뀐다(띄어쓰기 무시)")
    void bansBlame() {
        for (String bad : List.of("왜 안 왔어요…", "나를 두고 어디 갔었어", "너 때문에 배고팠어", "기다리게 했잖아", "왜안왔어", "실망했어요", "섭섭해")) {
            assertThat(BanFilter.isBanned(bad)).as(bad).isTrue();
            assertThat(BanFilter.clean(bad)).isEqualTo(BanFilter.SAFE_LINE);
        }
    }

    @Test
    @DisplayName("★ 리뷰 8문장 — 존댓말 변형·전각 공백·점으로 자른 것까지 잡는다")
    void reviewSentences() {
        for (String bad : List.of("왜 안 오셨어요?", "저를 잊으신 거예요", "많이 기다렸는데", "오늘도 안 오는 줄 알았어요",
                "외로웠어요… 어디 갔었어요?", "왜.안.왔.어", "왜\u3000안\u3000왔어요", "저 버리신 거 아니죠?")) {
            assertThat(BanFilter.isBanned(bad)).as(bad).isTrue();
        }
    }

    @Test
    @DisplayName("보통 말은 그대로")
    void passesNormal() {
        for (String ok : List.of("좋은 아침이에요!", "오늘 뭐 했어요?", "…응, 나도요.", "기억해 둘게요.")) {
            assertThat(BanFilter.isBanned(ok)).as(ok).isFalse();
            assertThat(BanFilter.clean(ok)).isEqualTo(ok);
        }
    }

    @Test
    @DisplayName("★ 템플릿 5그룹 × 부름 4 × 답 3 + 재언급 — 전부 필터를 지난다")
    void allTemplatesAreClean() {
        for (Personality p : Personality.values()) {
            for (ChatSlot s : ChatSlot.values()) {
                assertThat(BanFilter.isBanned(ChatTemplates.call(p, s, "여울"))).as(p + " " + s).isFalse();
            }
            for (String answer : List.of("가", "나다", "라마바")) {
                assertThat(BanFilter.isBanned(ChatTemplates.reply(p, answer, List.of(), 1))).as(p + " reply").isFalse();
            }
            assertThat(BanFilter.isBanned(ChatTemplates.reply(p, "x", List.of("어제 답"), 3))).as(p + " recall").isFalse();
            assertThat(ChatTemplates.reply(p, "x", List.of("어제 답"), 3)).contains("어제 답");
        }
        assertThat(ChatTemplates.call(null, ChatSlot.EVENING, "여울")).contains("여울");   // 성격 미선택 = 온순
    }
}
