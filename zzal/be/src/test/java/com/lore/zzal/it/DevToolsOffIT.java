package com.lore.zzal.it;

import com.lore.zzal.dev.DevBakeController;
import com.lore.zzal.dev.DevBakeService;
import com.lore.zzal.dev.DevClockController;
import com.lore.zzal.dev.DevOnboardingController;
import com.lore.zzal.dev.DevOnboardingService;
import com.lore.zzal.dev.DevPingController;
import com.lore.zzal.dev.DevService;
import com.lore.zzal.dev.DevStateController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 개발 도구 스위치 — <b>끄면 리모컨의 서버 쪽이 통째로 없다</b>(운영·스테이징의 모양).
 *
 * ★ 왜 따로 확인하나 — 리모컨은 값을 아무렇게나 바꾸는 주소다. 운영에 한 개라도 새면 누구나 제 펫을 4칸으로 채운다.
 *   컨트롤러뿐 아니라 서비스도 빈이 없어야 한다 — 서비스가 남아 있으면 언젠가 다른 길로 불린다.
 * ★ 화면은 ping 이 404 면 리모컨을 안 그린다(결정 3) — 그 404 가 여기서 고정된다.
 */
@ZzalIntegrationTest
@TestPropertySource(properties = "app.zzal.dev-tools=false")
@DisplayName("개발 도구 꺼짐 — 리모컨 빈·주소가 없다")
class DevToolsOffIT extends ZzalItSupport {

    @Test
    @DisplayName("★★ dev 컨트롤러·서비스 8종 모두 빈이 없다")
    void noDevBeans() {
        for (Class<?> type : List.of(DevClockController.class, DevStateController.class, DevBakeController.class,
                DevPingController.class, DevService.class, DevBakeService.class,
                DevOnboardingController.class, DevOnboardingService.class)) {
            assertThat(context.getBeanNamesForType(type)).as("%s 가 dev-tools=false 인데 떠 있다", type.getSimpleName())
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("★ ping·state·events·bake·advance-clock·me/reset·hatch/stage 모두 404")
    void everyAddressIsGone() throws Exception {
        Long userId = newUserId();
        assertThat(mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/zzal/v1/dev/ping").with(asUser(userId))).andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(postAs(userId, "/api/zzal/v1/dev/pets/1/events/sick", null).getResponse().getStatus()).isEqualTo(404);
        assertThat(postAs(userId, "/api/zzal/v1/dev/pets/1/bake", Map.of("kind", "gift")).getResponse().getStatus()).isEqualTo(404);
        assertThat(postAs(userId, "/api/zzal/v1/dev/pets/1/advance-clock", Map.of("minutes", 10)).getResponse().getStatus()).isEqualTo(404);
        assertThat(postAs(userId, "/api/zzal/v1/dev/me/reset", null).getResponse().getStatus()).isEqualTo(404);
        assertThat(postAs(userId, "/api/zzal/v1/dev/pets/1/hatch/stage", Map.of("stage", "done")).getResponse().getStatus()).isEqualTo(404);
    }
}
