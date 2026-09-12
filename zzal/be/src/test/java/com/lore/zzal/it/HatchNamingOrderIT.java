package com.lore.zzal.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.lore.zzal.generation.GenJob;
import com.lore.zzal.generation.GenJobRepository;
import com.lore.zzal.generation.GenKind;
import com.lore.zzal.motion.ZzalMotionRepository;
import com.lore.zzal.pet.PetPhase;
import com.lore.zzal.pet.ZzalPet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 시나리오 4 — <b>굽기 완료와 이름 제출, 두 순서 모두에서 같은 결과가 나온다.</b>
 *
 * <h3>왜 순서가 문제인가</h3>
 * 그림을 올리는 순간부터 <b>끝까지</b> 굽기 때문에(1.9), 사용자가 이름을 짓는 2~3분 사이에 굽기가 먼저
 * 끝날 수 있다. 그때 바로 살리면 <b>이름 없는 펫</b>이 방에 나타나고, 반대로 이름이 먼저 와도 그림이 없다.
 * 그래서 살아나는 조건은 <b>굽기 완료 + 이름 제출</b> 둘 다이고, <b>나중에 갖춰지는 쪽</b>이 살린다.
 * 그 판단({@code HatchService.completeIfReady})은 두 길에서 불리는데, 두 길 모두
 * {@code @TransactionalEventListener(AFTER_COMMIT)} 뒤에 있어 단위 시험으로는 밟히지 않는다.
 *
 * <h3>어느 계층인가</h3>
 * (a)·(c)는 컨트롤러(HTTP)까지 그대로 태운다.
 * (b)는 "이름이 먼저" 를 <b>우연에 맡기지 않기 위해</b> 초안을 직접 앉히고 굽기를 나중에 부른다 —
 * 이름 제출은 여전히 HTTP 이고, 살리는 판단도 진짜 {@code PetNamed} 이벤트를 지난다.
 */
@ZzalIntegrationTest
@DisplayName("시나리오 4 — 굽기 완료 ↔ 이름 제출, 두 순서")
class HatchNamingOrderIT extends ZzalItSupport {

    @Autowired GenJobRepository jobs;
    @Autowired ZzalMotionRepository motions;

    @Test
    @DisplayName("(a) 굽기가 먼저 끝나고 이름이 오면 그때 살아난다")
    void bakeFinishesFirstThenNameRevives() throws Exception {
        Long userId = newUserId();
        Long petId = draftOverHttp(userId);

        awaitBakeFinished(userId, petId);
        assertThat(phaseOf(petId)).as("굽기가 끝나도 이름이 없으면 초안 그대로다").isEqualTo(PetPhase.DRAFT);

        MvcResult named = postAs(userId, "/api/zzal/v1/me/pets/draft/%d/character".formatted(petId),
                Map.of("name", "여울"));
        assertThat(named.getResponse().getStatus()).isEqualTo(200);

        await("이름이 들어온 뒤 살아나는 것", () -> phaseOf(petId) == PetPhase.ALIVE);
        assertThat(motions.findByPetIdOrderBySeqAsc(petId))
                .as("부화 완료 = 동작 18행")
                .hasSize(18);
    }

    @Test
    @DisplayName("(b) 이름이 먼저 오고 굽기가 끝나면 그때 살아난다")
    void nameArrivesFirstThenBakeRevives() throws Exception {
        Long userId = newUserId();
        String version = hatchService.currentVersion();

        // ★ 이름이 먼저 오는 상황을 <b>우연에 맡기지 않는다</b> — 굽기를 아직 시작하지 않은 초안을 앉힌다.
        ZzalPet draft = transactions.execute(status ->
                petRepository.save(ZzalPet.draft(userId, newUploadedImageKey(userId), Instant.now())));
        Long petId = draft.getId();
        GenJob job = transactions.execute(status ->
                jobs.save(GenJob.start(petId, GenKind.HATCH, 1, version, Instant.now())));

        // 이름 제출은 진짜 API 다 — PetNamed 가 커밋 뒤에 completeIfReady 를 부르고, 아직 안 구웠으니 그냥 돌아간다.
        MvcResult named = postAs(userId, "/api/zzal/v1/me/pets/draft/%d/character".formatted(petId),
                Map.of("name", "여울"));
        assertThat(named.getResponse().getStatus()).isEqualTo(200);
        assertThat(phaseOf(petId)).as("이름만으로는 안 살아난다").isEqualTo(PetPhase.HATCHING);

        // 이제 굽는다(운영에서는 PetHatchListener 가 부르는 바로 그 호출).
        hatchService.hatch(job.getId(), petId, version);

        await("굽기가 끝난 뒤 살아나는 것", () -> phaseOf(petId) == PetPhase.ALIVE);
        assertThat(motions.findByPetIdOrderBySeqAsc(petId)).hasSize(18);
    }

    @Test
    @DisplayName("(c) 굽기만 끝나고 이름이 없으면 초안 그대로다 — 이름 없는 아이가 방에 나타나지 않는다")
    void bakeAloneDoesNotRevive() throws Exception {
        Long userId = newUserId();
        Long petId = draftOverHttp(userId);

        awaitBakeFinished(userId, petId);

        assertThat(phaseOf(petId)).isEqualTo(PetPhase.DRAFT);
        assertThat(motions.findByPetIdOrderBySeqAsc(petId))
                .as("살아나지 않았으니 동작 행도 아직 없다")
                .isEmpty();

        // 굽기가 끝난 뒤 초안을 다시 열면 <b>그 초안을 이어간다</b> — 새로 구우면 그림값이 또 나간다.
        JsonNode again = body(postAs(userId, "/api/zzal/v1/me/pets/draft",
                Map.of("imageKey", newUploadedImageKey(userId))));
        assertThat(again.path("data").path("petId").asLong()).isEqualTo(petId);
        assertThat(phaseOf(petId)).isEqualTo(PetPhase.DRAFT);
    }

    // ── 도우미 ────────────────────────────────────────────────────────────

    private Long draftOverHttp(Long userId) throws Exception {
        MvcResult result = postAs(userId, "/api/zzal/v1/me/pets/draft",
                Map.of("imageKey", newUploadedImageKey(userId)));
        assertThat(result.getResponse().getStatus())
                .as("초안 등록. 응답=%s", result.getResponse().getContentAsString())
                .isEqualTo(200);
        return body(result).path("data").path("petId").asLong();
    }

    /** 부화 진행 API 가 "모든 단계 끝" 을 말할 때까지. 안 오면 빨개진다. */
    private void awaitBakeFinished(Long userId, Long petId) {
        await("부화 5단계가 다 끝나는 것", Duration.ofSeconds(60), () -> {
            try {
                JsonNode hatch = getJson(userId, "/api/zzal/v1/me/pets/%d/hatch".formatted(petId))
                        .path("data");
                int total = hatch.path("total").asInt();
                return total > 0 && hatch.path("progress").asInt() >= total;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private PetPhase phaseOf(Long petId) {
        return petRepository.findById(petId).orElseThrow().getPhase();
    }
}
