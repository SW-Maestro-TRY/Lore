package com.lore.zzal.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.lore.zzal.motion.MotionStatus;
import com.lore.zzal.motion.ZzalMotion;
import com.lore.zzal.motion.ZzalMotionCandidateRepository;
import com.lore.zzal.motion.ZzalMotionRepository;
import com.lore.zzal.pet.CareAction;
import com.lore.zzal.pet.ZzalPet;
import com.lore.zzal.pet.ZzalRules;
import com.lore.zzal.piece.PieceKind;
import com.lore.zzal.piece.ZzalPiece;
import com.lore.zzal.piece.ZzalPieceRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 시나리오 3 — <b>조각 네 칸이 낮에 차는 순간 굽기가 실제로 시작된다.</b>
 *
 * <h3>이 한 줄이 지나는 자리</h3>
 * <pre>
 *   돌보기 API(HTTP)
 *     → PieceService.count        네 번째 칸이 찬다
 *     → PieceCompleted 이벤트      @TransactionalEventListener(AFTER_COMMIT)
 *     → PieceCompletedListener    돌보기가 커밋된 뒤에 깨어난다
 *     → BakeTrigger.onPieceComplete   @Transactional(REQUIRES_NEW)
 *     → NightPlanner.plan(PIECE)  큐 등록 + 조각 완성 소모
 *     → claim(UPDATE … WHERE QUEUED)  스위프와 겹치지 않게 상태로 집는다
 *     → nightExecutor             커밋 뒤에 던진다
 *     → MotionService.bakeNow     가짜 생성 → 게이트 → 검수 대기 + 후보 한 판
 * </pre>
 * 이 경로는 <b>단위 시험으로는 원리적으로 못 본다</b> — 커밋이 없으면 리스너가 안 깨어나고,
 * 진짜 트랜잭션이 없으면 {@code REQUIRES_NEW} 가 뜻을 잃는다. 오늘 두 번 터진 자리가 여기다.
 *
 * <h3>★ 어디까지가 진짜인가</h3>
 * 조각 네 칸 중 셋은 미리 채워 둔다 — 정본의 요구량이 <b>이틀치</b>라(밥 6회·목욕 2회 …) 한 번에 다 채울 수
 * 없기 때문이다. 마지막 한 칸을 채우는 것은 <b>진짜 돌보기 API</b> 한 번이고, 그 뒤는 전부 운영과 같은 코드다.
 */
@ZzalIntegrationTest
@DisplayName("시나리오 3 — 조각 4칸이 낮에 차면 굽기가 시작된다")
class PieceCompletionStartsBakeIT extends ZzalItSupport {

    @Autowired ZzalPieceRepository pieces;
    @Autowired ZzalMotionRepository motions;
    @Autowired ZzalMotionCandidateRepository candidates;

    @Test
    @DisplayName("마지막 조각을 채우는 돌보기 한 번이 굽기를 끝까지 굴린다")
    void lastPieceStartsTheBake() throws Exception {
        Long userId = newUserId();
        ZzalPet pet = aliveLayerTwoPet(userId);
        Long petId = pet.getId();

        // 1) 3층이 열린다 — 조회 한 번이 openPieces → enablePieces → 조각 줄 생성까지 진짜로 돈다.
        JsonNode detail = getJson(userId, "/api/zzal/v1/me/pets/" + petId);
        assertThat(detail.path("data").path("pieces").isNull())
                .as("2층 8종이 다 열렸으니 조각 4칸이 등장해야 한다. 응답=%s", detail)
                .isFalse();
        assertThat(pieces.findById(petId)).as("3층이 열리는 순간 조각 줄이 생긴다").isPresent();

        // 2) 네 칸 중 셋을 미리 채운다(요구량이 이틀치라 한 자리에서 다 채울 수 없다).
        //    ★ 도장은 정본 메서드(grant)로 찍는다 — 표를 직접 건드리지 않는다.
        //    마지막 한 칸(교감)은 쓰다듬기 한 번 남긴다.
        transactions.executeWithoutResult(status -> {
            ZzalPiece row = pieces.findById(petId).orElseThrow();
            row.grant(PieceKind.FOOD);
            row.grant(PieceKind.PLAY);
            row.grant(PieceKind.CLEAN);
            ReflectionTestUtils.setField(row, "petCount", ZzalRules.PIECE_PETS - 1);
        });
        assertThat(pieces.findById(petId).orElseThrow().isComplete()).isFalse();

        // 3) ★ 진짜 돌보기 API 한 번. 여기서부터 끝까지 운영과 같은 코드다.
        MvcResult cared = postAs(userId, "/api/zzal/v1/me/pets/%d/care".formatted(petId),
                Map.of("action", CareAction.PET.name()));
        assertThat(cared.getResponse().getStatus())
                .as("쓰다듬기는 거절이 없다. 응답=%s", cared.getResponse().getContentAsString())
                .isEqualTo(200);

        // 4) 네 칸이 찼다.
        assertThat(pieces.findById(petId).orElseThrow().isComplete())
                .as("쓰다듬기 한 번으로 마지막 칸이 찼어야 한다")
                .isTrue();

        // 5) 굽기가 실제로 시작돼 끝났다 — 큐 등록 → 집기 → 가짜 생성 → 검수 대기.
        //    ★ 실행기를 동기로 바꾸지 않았으므로 타임아웃 있는 대기로 받는다. 안 오면 빨개진다.
        await("조각이 찬 뒤 굽기가 검수 대기까지 가는 것", Duration.ofSeconds(30),
                () -> bakedRow(petId) != null);

        ZzalMotion baked = bakedRow(petId);
        assertThat(baked.getName())
                .as("app.zzal.advanced-motions 에 적힌 것 중 번호가 가장 앞선 동작이 오른다")
                .isEqualTo("pet");
        assertThat(baked.getImageKey()).as("구운 결과물의 자리").isNotBlank();
        assertThat(candidates.findByMotionIdOrderByRoundAscIdAsc(baked.getId()))
                .as("판정 화면이 볼 후보가 한 판 남아야 한다")
                .hasSize(1);

        // 6) 완성은 <b>소모</b>됐다 — 이것이 없으면 같은 판으로 두 번 굽고 돈이 두 배로 나간다.
        assertThat(pieces.findById(petId).orElseThrow().isConsumed())
                .as("굽기가 이 완성을 가져갔다는 표시")
                .isTrue();

        // 7) 그 완성은 다음 기상에 비워진다 — 개발 시계로 하룻밤을 민다(운영에 있는 그 장치 그대로).
        advanceClock(userId, petId, Duration.ofHours(24));
        ZzalPiece afterWake = pieces.findById(petId).orElseThrow();
        assertThat(afterWake.isConsumed()).as("쓰인 완성은 기상에 되돌아간다").isFalse();
        assertThat(afterWake.isDone(PieceKind.BOND))
                .as("네 칸이 0 으로 돌아간다(기분 좋은 날 선물은 앞선 칸부터 채우므로 교감 칸은 남지 않는다)")
                .isFalse();
    }

    /** 이 펫에서 굽기가 끝나 검수 대기(REVIEW)로 간 줄. 아직이면 null. */
    private ZzalMotion bakedRow(Long petId) {
        return motions.findByPetIdOrderBySeqAsc(petId).stream()
                .filter(m -> m.getStatus() == MotionStatus.REVIEW)
                .findFirst()
                .orElse(null);
    }
}
