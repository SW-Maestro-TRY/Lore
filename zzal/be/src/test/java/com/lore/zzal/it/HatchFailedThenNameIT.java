package com.lore.zzal.it;

import com.lore.common.exception.ErrorCode;
import com.lore.zzal.generation.GenerationRecorder;
import com.lore.zzal.pet.PetPhase;
import com.lore.zzal.pet.ZzalPet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 시나리오 5 — <b>이름 짓는 중 굽기가 실패했으면, 이름을 내밀 때 그 사실을 말해 준다.</b>
 *
 * <h3>왜 이 자리가 위험한가</h3>
 * 그림을 올리는 순간부터 굽기 때문에, 이름을 짓는 2~3분 사이에 <b>실패가 끝나 있을 수 있다</b>.
 * 그때 "이미 이름을 지은 아이예요"({@code ZZAL_PET_NOT_DRAFT})라고 답하면, 이름을 방금 처음 지은 사람에게
 * <b>사실과 정반대</b>로 말하게 되고 다음에 무엇을 해야 하는지도 알 수 없다.
 *
 * <h3>★ 상수 둘이 다르다는 것만 보지 않는다</h3>
 * 가드가 실제로 도는지는 <b>서비스를 불러 봐야</b> 안다. 여기서는 컨트롤러(HTTP)까지 태워
 * 응답의 {@code error.code} 와 상태 코드를 함께 본다.
 *
 * <h3>그리고 자리를 안 먹는다</h3>
 * 태어나지 못한 알이 칸을 차지하면 그 사람은 <b>영영 다시 시작할 수 없다.</b> 그래서 실패 뒤 새 초안이
 * 만들어지는 것까지 이어서 본다.
 */
@ZzalIntegrationTest
@DisplayName("시나리오 5 — 굽기 실패 뒤 이름 제출 → ZZAL_PET_HATCH_FAILED")
class HatchFailedThenNameIT extends ZzalItSupport {

    @Autowired GenerationRecorder recorder;

    @Test
    @DisplayName("실패한 알에 이름을 내밀면 실패를 말해 주고, 새 초안은 만들 수 있다")
    void namingAFailedEggTellsTheTruthAndLeavesTheSlotFree() throws Exception {
        Long userId = newUserId();

        // 이름을 아직 안 지은 초안 하나.
        ZzalPet draft = transactions.execute(status ->
                petRepository.save(ZzalPet.draft(userId, newUploadedImageKey(userId), Instant.now())));
        Long petId = draft.getId();

        // 굽기가 끝내 실패했다 — 운영에서 HatchService 가 시도를 다 쓰고 부르는 바로 그 길이다.
        recorder.markPetFailed(petId);
        assertThat(petRepository.findById(petId).orElseThrow().getPhase()).isEqualTo(PetPhase.FAILED);

        // ★ 그 뒤에 이름이 온다. 상수 비교가 아니라 진짜로 불러 본다.
        MvcResult named = postAs(userId, "/api/zzal/v1/me/pets/draft/%d/character".formatted(petId),
                Map.of("name", "여울"));

        assertThat(named.getResponse().getStatus())
                .as("실패는 409 로 답한다")
                .isEqualTo(ErrorCode.ZZAL_PET_HATCH_FAILED.getStatus().value());
        assertThat(errorCode(named))
                .as("\"이미 이름을 지은 아이예요\"(ZZAL_PET_NOT_DRAFT)로 답하면 사실과 정반대다")
                .isEqualTo(ErrorCode.ZZAL_PET_HATCH_FAILED.name());

        // 이름은 저장되지 않았다 — 실패한 알에 이름만 붙어 있으면 화면이 그 아이를 살아 있는 것처럼 읽는다.
        ZzalPet failed = petRepository.findById(petId).orElseThrow();
        assertThat(failed.getName()).isBlank();
        assertThat(failed.getPhase()).isEqualTo(PetPhase.FAILED);

        // ★ 태어나지 못한 알은 자리를 안 먹는다 — 새 그림으로 다시 시작할 수 있어야 한다.
        MvcResult fresh = postAs(userId, "/api/zzal/v1/me/pets/draft",
                Map.of("imageKey", newUploadedImageKey(userId)));
        assertThat(fresh.getResponse().getStatus())
                .as("자리가 없다(ZZAL_PET_LIMIT_REACHED)고 하면 그 사람은 영영 다시 못 한다. 응답=%s",
                        fresh.getResponse().getContentAsString())
                .isEqualTo(200);

        Long newPetId = body(fresh).path("data").path("petId").asLong();
        assertThat(newPetId).as("실패한 알을 이어가지 않고 새 초안을 만든다").isNotEqualTo(petId);
        assertThat(petRepository.countByUserIdAndPhaseIn(userId, PetPhase.OCCUPYING_SLOT))
                .as("자리를 차지하는 것은 새 초안 하나뿐이다")
                .isEqualTo(1);
    }
}
