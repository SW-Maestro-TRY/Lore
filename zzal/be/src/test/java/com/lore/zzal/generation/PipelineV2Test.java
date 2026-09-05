package com.lore.zzal.generation;

import com.lore.zzal.generation.client.PostProcessor;
import com.lore.zzal.generation.steps.GridStep;
import com.lore.zzal.generation.steps.IdentityStep;
import com.lore.zzal.generation.steps.MotionGridStep;
import com.lore.zzal.generation.steps.MotionPostStep;
import com.lore.zzal.generation.steps.PostProcessStep;
import com.lore.zzal.generation.steps.SheetStep;
import com.lore.zzal.motion.MotionCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 부화 파이프라인 v2 — 격자 2장 + 후처리 v2, 프롬프트 없으면 v1 폴백.
 *
 * ★ "설정은 v2 인데 조용히 v1 로 도는" 것을 막는다 — 폴백은 되, 로그와 기록(currentVersion)이 v1 을 말해야 한다.
 */
@DisplayName("부화 파이프라인 v2")
class PipelineV2Test {

    private PipelineRegistry registry(String hatchVersion, boolean v2PromptsExist) {
        GridStep grid = mock(GridStep.class);
        GridStep grid2 = mock(GridStep.class);
        return new PipelineRegistry(mock(SheetStep.class), mock(IdentityStep.class), grid, grid2, mock(PostProcessStep.class),
                mock(MotionGridStep.class), mock(MotionPostStep.class), hatchVersion, "v1", path -> v2PromptsExist);
    }

    @Test
    @DisplayName("v2 = sheet → identity → grid → grid2 → post (5단계)")
    void v2HasFiveSteps() {
        PipelineRegistry r = registry("v2", true);
        assertThat(r.currentVersion(GenKind.HATCH)).isEqualTo("v2");
        assertThat(r.steps(GenKind.HATCH, "v2")).hasSize(5);
        assertThat(r.steps(GenKind.HATCH, "v1")).hasSize(4);
    }

    @Test
    @DisplayName("★ v2 를 켰는데 prompt/v2/*.txt 가 없으면 v1 로 기동 — 기록도 v1")
    void fallsBackToV1WhenPromptsMissing() {
        PipelineRegistry r = registry("v2", false);
        assertThat(r.currentVersion(GenKind.HATCH)).isEqualTo("v1");
    }

    @Test
    @DisplayName("v1 설정은 프롬프트와 무관하게 v1")
    void v1Unchanged() {
        assertThat(registry("v1", false).currentVersion(GenKind.HATCH)).isEqualTo("v1");
    }

    @Test
    @DisplayName("★ 후처리 v2 — grid·grid2 를 각각 카탈로그 key 8개로 basic/ 에 자른다. grid2 가 없으면 v1(8상태)")
    void postProcessSplitsTwoGridsWithKeys() throws Exception {
        PostProcessor post = mock(PostProcessor.class);
        MotionCatalog catalog = new MotionCatalog("", "", "v1");
        PostProcessStep step = new PostProcessStep(post, catalog);

        StepContext v2 = new StepContext(7L, "여울", null, "v2");
        v2.putImage(GridStep.NAME, "images/zzal/pets/7/grid.png");
        v2.putImage(PostProcessStep.GRID2, "images/zzal/pets/7/grid2.png");
        step.run(v2);
        InOrder order = inOrder(post);
        order.verify(post).split("images/zzal/pets/7/grid.png", "images/zzal/pets/7/basic", "v2",
                List.of("base", "eat", "joy", "sad", "sick", "practice", "shy", "call"));
        order.verify(post).split("images/zzal/pets/7/grid2.png", "images/zzal/pets/7/basic", "v2",
                List.of("tilt", "wave", "sleep", "wash", "startle", "nod", "smile_idle", "sit"));

        PostProcessor postV1 = mock(PostProcessor.class);
        StepContext v1 = new StepContext(7L, "여울", null, "v1");
        v1.putImage(GridStep.NAME, "images/zzal/pets/7/grid.png");
        new PostProcessStep(postV1, catalog).run(v1);
        verify(postV1).split("images/zzal/pets/7/grid.png", "images/zzal/pets/7", "v1");   // ★ job 의 버전을 넘긴다(폴백 안전)
        verify(postV1, never()).split(anyString(), anyString(), anyString(), anyList());
    }
}
