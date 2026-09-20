package com.lore.zzal.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.lore.zzal.feedback.FeedbackController;
import com.lore.zzal.pet.ZzalPet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 후기 스위치 — <b>켠 쪽</b>. 기본값이 켬이 된 뒤(2026-09-14) 주소 둘이 실제로 응답하는지 본다.
 *
 * <h3>★ 왜 스위치를 시험으로 두나</h3>
 * 이건 운영에서 다시 끌 수 있는 스위치라 <b>꺼진 상태가 실제로 생긴다</b>. 켠 쪽만 보면
 * 껐을 때 어떻게 되는지를 아무도 모른 채 배포된다 — 꺼진 쪽은 {@link FeedbackOffIT} 가 본다.
 */
@ZzalIntegrationTest
@DisplayName("후기 스위치 — 켜면 주소 둘이 산다")
class FeedbackSwitchIT extends ZzalItSupport {

    private ZzalPet alivePet(Long userId) {
        Instant anchor = kstToday(11, 0);
        ZzalPet pet = transactions.execute(status -> {
            ZzalPet created = petRepository.save(ZzalPet.draft(userId, newUploadedImageKey(userId), anchor));
            created.character("여울", null, null, null, null, null, anchor);
            created.markAlive("images/zzal/pets/sheet.png", "(시험용 정체성 문단)", anchor);
            created.skipTutorial(anchor);
            return created;
        });
        pinClock(pet.getId(), anchor);
        return pet;
    }

    @Test
    @DisplayName("★ 컨트롤러가 빈으로 올라와 있다 — 스위치가 꺼지면 여기가 비어 주소 자체가 없다")
    void theControllerIsWired() {
        assertThat(context.getBeanNamesForType(FeedbackController.class)).isNotEmpty();
    }

    @Test
    @DisplayName("★★ 후기를 남기고 그대로 조회된다 — 자유 글까지 남는다")
    void submitThenRead() throws Exception {
        Long userId = newUserId();
        Long petId = alivePet(userId).getId();
        String path = "/api/zzal/v1/me/pets/%d/feedback".formatted(petId);

        MvcResult submitted = postAs(userId, path, Map.of(
                "rating", 4,
                "tags", List.of("LOOKS_SAME", "MOTION_ODD"),
                "text", "움직임이 생각보다 자연스러웠어요"));

        assertThat(submitted.getResponse().getStatus()).isEqualTo(200);
        JsonNode data = body(submitted).path("data");
        assertThat(data.path("submitted").asBoolean()).isTrue();
        assertThat(data.path("rating").asInt()).isEqualTo(4);

        JsonNode mine = getJson(userId, path).path("data");
        assertThat(mine.path("submitted").asBoolean()).isTrue();
        assertThat(mine.path("rating").asInt()).isEqualTo(4);
        assertThat(mine.path("text").asText()).contains("자연스러웠어요");
    }

    @Test
    @DisplayName("★ 안 낸 사람에게는 에러가 아니라 'submitted=false' 다 — 화면이 칸을 띄울지 정하는 값")
    void nothingSubmittedYet() throws Exception {
        Long userId = newUserId();
        Long petId = alivePet(userId).getId();

        JsonNode mine = getJson(userId, "/api/zzal/v1/me/pets/%d/feedback".formatted(petId)).path("data");

        assertThat(mine.path("submitted").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("★ 두 번째는 409 — 한 사람이 한 펫에 한 번이다")
    void onlyOncePerPet() throws Exception {
        Long userId = newUserId();
        Long petId = alivePet(userId).getId();
        String path = "/api/zzal/v1/me/pets/%d/feedback".formatted(petId);
        postAs(userId, path, Map.of("rating", 5));

        MvcResult again = postAs(userId, path, Map.of("rating", 3));

        assertThat(again.getResponse().getStatus()).isEqualTo(409);
        assertThat(errorCode(again)).isEqualTo("ZZAL_FEEDBACK_ALREADY_SUBMITTED");
    }
}
