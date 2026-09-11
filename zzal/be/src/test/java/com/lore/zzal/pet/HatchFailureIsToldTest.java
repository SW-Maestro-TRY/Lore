package com.lore.zzal.pet;

import com.lore.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 이름 짓는 동안 굽기가 실패하면 <b>죽는다. 대신 그 사실이 제대로 전해져야 한다</b>(1.9).
 *
 * <h3>★ 죽는 것 자체는 맞다</h3>
 * 이름은 그림 생성에 안 들어간다. 그래서 이름을 기다렸다 다시 굽는 것은
 * <b>돈을 늦게 쓸 뿐 이득이 없다</b>(상훈님 2026-09-11). 두 번 실패하면 거기서 끝난다.
 *
 * <h3>★★ 문제는 어떻게 알려지느냐였다</h3>
 * 그림을 올리는 순간부터 굽기 때문에, 이름을 짓는 <b>2~3분</b>(실측 2분 54초)이 굽기와 겹친다.
 * 그 사이에 조용히 실패하면 사용자는 이름을 다 짓고 제출하는 순간 이런 답을 받았다.
 *
 * <pre>409 ZZAL_PET_NOT_DRAFT  "이미 이름을 지은 아이예요"</pre>
 *
 * <b>사실과 정반대다.</b> 이름을 방금 처음 지은 사람에게 이미 지었다고 말하고,
 * 무슨 일이 일어났는지도 다음에 뭘 해야 하는지도 알 수 없다.
 *
 * <h3>★ 문구는 우리 탓으로 말한다</h3>
 * "얼굴이 안 보여서" · "품질이 낮아서" 처럼 <b>사용자의 그림을 탓하는 말을 쓰지 않는다</b>(자캐 커뮤니티 규범).
 */
@DisplayName("굽기 실패는 죽되, 사용자가 그 사실을 알 수 있어야 한다")
class HatchFailureIsToldTest {

    private static final Instant T0 = Instant.parse("2026-09-11T03:00:00Z");

    private ZzalPet draft() {
        return ZzalPet.draft(1L, "images/zzal/src", T0);
    }

    @Test
    @DisplayName("이름 전에 실패하면 초안도 FAILED 가 된다 — 기다렸다 다시 굽는 것은 이득이 없다")
    void draftDiesOnFailure() {
        ZzalPet pet = draft();

        pet.markHatchFailed();

        assertThat(pet.getPhase()).isEqualTo(PetPhase.FAILED);
        assertThat(pet.getDeathReason()).isEqualTo(DeathReason.HATCH_FAILED);
    }

    @Test
    @DisplayName("★★ 실패한 펫에게 쓸 오류는 '이미 이름을 지었다'가 아니다")
    void failedPetGetsItsOwnError() {
        // 두 코드가 갈려 있지 않으면 이름을 처음 지은 사람에게 사실과 반대로 말하게 된다.
        assertThat(ErrorCode.ZZAL_PET_HATCH_FAILED)
                .isNotEqualTo(ErrorCode.ZZAL_PET_NOT_DRAFT);
        assertThat(ErrorCode.ZZAL_PET_HATCH_FAILED.getDefaultMessage())
                .isNotEqualTo(ErrorCode.ZZAL_PET_NOT_DRAFT.getDefaultMessage());
    }

    @Test
    @DisplayName("★ 실패 문구가 사용자의 그림을 탓하지 않는다")
    void messageDoesNotBlameTheDrawing() {
        String message = ErrorCode.ZZAL_PET_HATCH_FAILED.getDefaultMessage();

        assertThat(message).contains("다시");                 // 다음에 할 일을 말한다
        assertThat(message).doesNotContain("품질");
        assertThat(message).doesNotContain("얼굴");
        assertThat(message).doesNotContain("나쁜");
        assertThat(message).doesNotContain("부적절");
    }

    @Nested
    @DisplayName("다시 올릴 수 있어야 한다")
    class CanStartOver {

        @Test
        @DisplayName("★★ FAILED 는 슬롯을 먹지 않는다 — 여기 들어가면 사용자가 갇힌다")
        void failedDoesNotOccupySlot() {
            // 슬롯이 하나인 사용자가 실패한 뒤 새 그림을 올릴 수 있어야 한다.
            // FAILED 가 이 목록에 들어가면 "자리가 없다" 로 막혀 아무것도 못 하게 된다.
            assertThat(PetPhase.OCCUPYING_SLOT).doesNotContain(PetPhase.FAILED);
            assertThat(PetPhase.OCCUPYING_SLOT)
                    .containsExactlyInAnyOrder(PetPhase.DRAFT, PetPhase.HATCHING, PetPhase.ALIVE);
        }

        @Test
        @DisplayName("실패한 펫은 초안 재사용 대상이 아니다 — 새 그림으로 시작한다")
        void failedIsNotReusedAsDraft() {
            ZzalPet pet = draft();
            pet.markHatchFailed();

            assertThat(pet.isDraft()).isFalse();
        }
    }
}
