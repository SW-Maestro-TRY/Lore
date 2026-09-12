package com.lore.zzal.generation;

import com.lore.zzal.generation.steps.GridStep;
import com.lore.zzal.generation.steps.IdentityStep;
import com.lore.zzal.generation.steps.MotionGridStep;
import com.lore.zzal.generation.steps.MotionPostStep;
import com.lore.zzal.generation.steps.PostProcessStep;
import com.lore.zzal.generation.steps.SheetStep;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 거부(MODERATION)로 문단을 새로 만들 때 <b>그 문단을 쓴 단계도 함께</b> 폐기한다.
 *
 * <h3>★★ 문단만 지우면 격자 두 장의 근거가 갈린다</h3>
 * 재시도는 성공한 단계를 건너뛴다. 1층 격자가 성공하고 2층만 거부된 경우, 문단만 지우면
 * <b>1층은 옛 문단으로 구운 그림을 그대로 쓰고 2층만 새 문단으로</b> 구워진다.
 * 같은 아이인데 두 격자의 묘사 근거가 달라, 1층과 2층의 생김새가 어긋난 채 사용자에게 간다.
 * <b>예외가 안 나므로 그림을 열어 봐야만 드러난다.</b>
 *
 * <h3>★ 왜 단계가 스스로 말하게 하지 않나</h3>
 * 단계마다 "나는 문단을 쓴다" 를 선언하게 하면 기본값이 필요하고, 새 단계가 그 선언을 빠뜨리면
 * 조용히 틀린다. 파이프라인의 모양이 한 파일에 있으므로 이 의존도 거기 눈에 보이게 뒀다.
 */
@DisplayName("거부 재시도 — 문단과 그것을 쓴 단계를 함께 폐기한다")
class ModerationDiscardTest {

    private PipelineRegistry registry(String hatchVersion) {
        return new PipelineRegistry(
                mock(SheetStep.class), StepMocks.identity(),
                new GridStep(null, null, GridStep.NAME),
                new GridStep(null, null, PostProcessStep.GRID2),
                mock(PostProcessStep.class),
                mock(MotionGridStep.class), mock(MotionPostStep.class), hatchVersion, "v1", path -> true);
    }

    @Test
    @DisplayName("★★ v2 — 문단과 격자 두 장이 함께 폐기 대상이다")
    void v2DiscardsBothGrids() {
        List<String> targets = registry("v2").identityDependents(GenKind.HATCH, "v2");

        assertThat(targets)
                .as("2층만 거부됐을 때 1층이 남으면 두 격자의 근거가 갈린다")
                .containsExactlyInAnyOrder(IdentityStep.NAME, GridStep.NAME, PostProcessStep.GRID2);
    }

    @Test
    @DisplayName("v1 — 격자가 한 장뿐이라 그 한 장만 함께 폐기한다")
    void v1DiscardsSingleGrid() {
        List<String> targets = registry("v1").identityDependents(GenKind.HATCH, "v1");

        assertThat(targets).containsExactlyInAnyOrder(IdentityStep.NAME, GridStep.NAME);
        assertThat(targets).doesNotContain(PostProcessStep.GRID2);
    }

    @Test
    @DisplayName("★ 후처리는 폐기 대상이 아니다 — 격자 그림만 보고, 앞이 실패하면 도달하지 못한다")
    void postProcessIsNotDiscarded() {
        List<String> targets = registry("v2").identityDependents(GenKind.HATCH, "v2");

        assertThat(targets).doesNotContain(PostProcessStep.NAME);
    }

    @Test
    @DisplayName("모션은 문단을 쓰지 않는다")
    void motionHasNoIdentityDependents() {
        assertThat(registry("v2").identityDependents(GenKind.MOTION, "v1")).isEmpty();
    }

    @Test
    @DisplayName("★★ 적어 둔 이름이 그 버전에 없으면 기동에서 막는다 — 조용히 거르면 어긋난 격자가 나간다")
    void bootFailsWhenDeclaredNameIsMissing() {
        // 문단 단계의 이름이 어긋난 척한다(이름을 바꾸고 선언을 안 고친 상황).
        IdentityStep renamed = StepMocks.identity();
        when(renamed.name()).thenReturn("identity_v3");

        assertThatThrownBy(() -> new PipelineRegistry(
                mock(SheetStep.class), renamed,
                new GridStep(null, null, GridStep.NAME),
                new GridStep(null, null, PostProcessStep.GRID2),
                mock(PostProcessStep.class),
                mock(MotionGridStep.class), mock(MotionPostStep.class), "v2", "v1", path -> true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("identity");
    }
}
