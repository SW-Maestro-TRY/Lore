package com.lore.zzal.it;

import com.lore.zzal.feedback.FeedbackController;
import com.lore.zzal.feedback.FeedbackService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 후기 스위치 — <b>끈 쪽</b>. 껐을 때 <b>조용히 망가지는 것이 아니라 주소가 없는 것</b>이어야 한다.
 *
 * <h3>★★ 왜 이 시험이 필요한가</h3>
 * 껐을 때의 모양에는 두 가지가 있다 — (1) 주소가 아예 없다(404) (2) 주소는 있는데 아무 일도
 * 안 일어나거나 500 이 난다. 후자면 화면은 <b>기능이 있다고 믿고</b> 호출하고, 사용자는 원인을
 * 알 수 없는 실패를 본다. 이 스위치는 {@code @ConditionalOnProperty} 라 빈 자체가 안 올라와
 * (1) 이 되는데, 그건 지금 그렇다는 뜻일 뿐이라 <b>못을 박아 둔다</b>.
 *
 * <h3>★ 서비스는 그대로 살아 있다</h3>
 * 스위치는 <b>주소</b>만 닫는다. 표도 서비스도 그대로라, 다시 켜면 그동안 쌓인 것이 그대로 보인다.
 */
@ZzalIntegrationTest
@TestPropertySource(properties = "app.zzal.feedback.enabled=false")
@DisplayName("후기 스위치 — 끄면 주소가 없다")
class FeedbackOffIT extends ZzalItSupport {

    @Test
    @DisplayName("★★ 컨트롤러가 빈으로 안 올라온다 — 코드가 올라와 있으면 언젠가 불린다")
    void theControllerIsNotWired() {
        assertThat(context.getBeanNamesForType(FeedbackController.class))
                .as("app.zzal.feedback.enabled 가 false 면 이 컨트롤러는 없다")
                .isEmpty();
    }

    @Test
    @DisplayName("★★ 두 주소 모두 404 다 — 500 도, 조용한 200 도 아니다")
    void bothAddressesAreGone() throws Exception {
        Long userId = newUserId();
        String path = "/api/zzal/v1/me/pets/1/feedback";

        MvcResult posted = postAs(userId, path, Map.of("rating", 4));
        assertThat(posted.getResponse().getStatus()).isEqualTo(404);

        MvcResult got = mockMvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get(path).with(asUser(userId))).andReturn();
        assertThat(got.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("★ 서비스와 표는 그대로다 — 스위치는 주소만 닫는다(다시 켜면 쌓인 것이 보인다)")
    void theServiceItselfStaysAlive() {
        assertThat(context.getBeanNamesForType(FeedbackService.class)).isNotEmpty();
    }
}
