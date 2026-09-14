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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
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
        GridStep grid = StepMocks.grid();
        GridStep grid2 = StepMocks.grid2();
        return new PipelineRegistry(mock(SheetStep.class), StepMocks.identity(), grid, grid2, mock(PostProcessStep.class),
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
    @DisplayName("★ v2 묶음 — 격자 두 장만 한 묶음(나란히), identity 는 그 앞 묶음이라 반드시 먼저 끝난다")
    void v2RunsBothGridsTogether() {
        PipelineRegistry r = registry("v2", true);
        List<List<GenerationStep>> stages = r.stages(GenKind.HATCH, "v2");

        assertThat(stages).hasSize(4);
        assertThat(stages.get(2)).hasSize(2);                     // grid · grid2
        assertThat(stages).allSatisfy(stage -> assertThat(stage).isNotEmpty());
        assertThat(stages.get(0)).hasSize(1);                     // sheet
        assertThat(stages.get(1)).hasSize(1);                     // identity — 격자보다 앞
        assertThat(stages.get(3)).hasSize(1);                     // post — 격자 뒤

        // v1 은 격자가 한 장이라 나란히 돌 것이 없다
        assertThat(r.stages(GenKind.HATCH, "v1")).allSatisfy(stage -> assertThat(stage).hasSize(1));
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
    @DisplayName("★ 후처리 v2 — grid·grid2 를 각각 카탈로그 key 8개로 basic/{판} 에 자른다. grid2 가 없으면 v1(8상태)")
    void postProcessSplitsTwoGridsWithKeys() throws Exception {
        PostProcessor post = mock(PostProcessor.class);
        PostProcessor.Session session = mock(PostProcessor.Session.class);
        when(post.open(anyString(), anyString())).thenReturn(session);
        MotionCatalog catalog = new MotionCatalog("", "", "v1");
        GenerationRecorder recorder = mock(GenerationRecorder.class);
        when(recorder.nextBasicRound(7L)).thenReturn(3);
        PostProcessStep step = new PostProcessStep(post, catalog, new HatchPostures(), recorder);

        StepContext v2 = new StepContext(7L, "여울", null, "v2");
        v2.putImage(GridStep.NAME, "images/zzal/pets/7/grid.png");
        v2.putImage(PostProcessStep.GRID2, "images/zzal/pets/7/grid2.png");
        step.run(v2);
        // ★ 두 층이 <b>한 세션</b>이어야 2층이 1층 앵커에 합쳐 쓴다. 층마다 세션을 열면 폴더가 갈린다.
        verify(post, times(1)).open("images/zzal/pets/7/basic/3", "v2");
        InOrder order = inOrder(session);
        order.verify(session).split("images/zzal/pets/7/grid.png",
                List.of("base", "eat", "joy", "sad", "sick", "pet", "hello", "sleep"));
        order.verify(session).split("images/zzal/pets/7/grid2.png",
                List.of("eat_rice", "eat_snack", "sweep", "wash", "reply", "petted", "startle", "wake_up"));
        order.verify(session).close();
        // ★ 판은 세션이 닫힌 <b>뒤에</b> 오른다 — 먼저 올리면 앵커가 빠진 판을 화면이 먼저 받는다.
        verify(recorder).markBasicBaked(7L, 3);

        PostProcessor postV1 = mock(PostProcessor.class);
        PostProcessor.Session sessionV1 = mock(PostProcessor.Session.class);
        when(postV1.open(anyString(), anyString())).thenReturn(sessionV1);
        GenerationRecorder recorderV1 = mock(GenerationRecorder.class);
        when(recorderV1.nextBasicRound(7L)).thenReturn(1);
        StepContext v1 = new StepContext(7L, "여울", null, "v1");
        v1.putImage(GridStep.NAME, "images/zzal/pets/7/grid.png");
        new PostProcessStep(postV1, catalog, new HatchPostures(), recorderV1).run(v1);
        // ★ v1 은 basic/ 규약 이전이라 판 칸이 없다. job 의 버전을 넘긴다(폴백 안전).
        verify(postV1).open("images/zzal/pets/7", "v1");
        verify(sessionV1).split("images/zzal/pets/7/grid.png");
        verify(sessionV1, never()).split(anyString(), anyList());
        // ★ 판을 올리지 않는다 — 옛 규약에는 판이 없어서, 올려 두면 다음에 새 주소로 튄다.
        verify(recorderV1, never()).markBasicBaked(anyLong(), anyInt());
    }
}
