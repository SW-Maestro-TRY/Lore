package com.lore.zzal.generation;

import com.lore.zzal.alert.ZzalAlerts;
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
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.mockito.InOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;

/**
 * 부화 파이프라인 v1 — 1층·2층 각 8종(2026-09-12 검수 확정 조합).
 *
 * <h3>★ 이 테스트가 지키는 것 = 이름이 어긋나 조용히 죽는 길을 막는 것</h3>
 * 계정·파일·버전을 옮기면 이름(설정·상수·경로)이 어긋나는데, 빌드·배포·기동이 전부 통과한 채
 * <b>실제로 부화가 도는 순간에만</b> 터진다. 그래서 서로 같아야 하는 값들을 여기서 묶어 둔다.
 *   1) 게이트 사양(pipeline/v1/grid_spec.txt) ↔ 실제 프롬프트(prompt/v1/grid.txt) 첫 줄
 *   2) 스크립트가 찍는 표식 ↔ 자바가 찾는 표식
 *   3) 후처리가 내놓는 파일 이름(state8_v5.KEYS) ↔ 설정에 적어야 하는 hatch.states.v1
 *   4) 칸의 자세 매핑(pipeline/v1/postures.txt) ↔ 확정된 그 조합 · ↔ 2층 key 순서
 */
@DisplayName("부화 파이프라인 v1 — 1층·2층 16종")
class HatchPipelineV1Test {

    /**
     * 설정 {@code app.zzal.hatch.states.v1} 에 적어야 하는 값.
     *
     * ★ 검수를 마친 확정 조합을 <b>시험 안에</b> 손으로 못박아 둔다. 카탈로그·설정·자세 매핑이
     *   다 같이 틀리면 서로 대조해 봐야 못 잡으므로, 독립된 기준 하나가 필요하다.
     */
    private static final List<String> LAYER1 =
            List.of("base", "eat", "joy", "sad", "sick", "pet", "hello", "sleep");

    /**
     * 2층 8종의 기준 순서 = 격자 칸 순서 = 파일 이름.
     *
     * ★ {@code MotionCatalog} 의 BASIC_2 도 이 순서·이 이름이어야 한다(다른 갈래가 맞춘다).
     *   어긋나면 후처리가 {@code --postures 의 'wash' 가 --keys 에 없습니다} 로 멈춘다 —
     *   조용히 엉뚱한 그림이 들어가는 것보다 멈추는 편이 낫다.
     */
    private static final List<String> LAYER2 =
            List.of("eat_rice", "eat_snack", "sweep", "wash", "reply", "petted", "startle", "wake_up");

    private static String resource(String path) throws IOException {
        return new String(new ClassPathResource(path).getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("v1 = sheet → identity → grid → grid2 → post (5단계)")
    void hatchHasFiveSteps() {
        // ★ 이름이 채워진 목을 쓴다 — 레지스트리가 기동할 때 "문단에 기대는 단계" 의 이름이
        //   실제로 그 버전에 있는지 확인하므로, name() 이 null 인 맨 목이면 그 확인에서 터진다.
        PipelineRegistry r = new PipelineRegistry(StepMocks.sheet(), StepMocks.identity(),
                StepMocks.grid(), StepMocks.grid2(), StepMocks.post(),
                mock(MotionGridStep.class), mock(MotionPostStep.class), "v1", "v1");

        assertThat(r.currentVersion(GenKind.HATCH)).isEqualTo("v1");
        assertThat(r.steps(GenKind.HATCH, "v1")).hasSize(5);
    }

    @Test
    @DisplayName("★ 후처리 — 격자 2장을 한 세션 안에서 basic/{판} 아래에 자르며 칸의 자세 매핑을 함께 넘긴다")
    void splitsTwoGridsWithPostures() throws Exception {
        PostProcessor post = mock(PostProcessor.class);
        PostProcessor.Session session = mock(PostProcessor.Session.class);
        when(post.open(anyString(), anyString())).thenReturn(session);
        MotionCatalog catalog = new MotionCatalog("", "", "v1");
        HatchPostures postures = new HatchPostures();
        GenerationRecorder recorder = mock(GenerationRecorder.class);
        when(recorder.nextBasicRound(7L)).thenReturn(2);
        PostProcessStep step = new PostProcessStep(post, catalog, postures, recorder);

        StepContext ctx = new StepContext(7L, "여울", null, "v1");
        ctx.putImage(GridStep.NAME, "images/zzal/pets/7/grid.png");
        ctx.putImage(PostProcessStep.GRID2, "images/zzal/pets/7/grid2.png");
        step.run(ctx);

        // ★ 화면이 basicImageKey 를 .../basic/{판}/{key}.webp 로 조립한다 — 자리가 어긋나면 그림이 안 뜬다.
        // ★★ 두 층이 <b>한 세션</b>이어야 한다. 층마다 열면 작업 폴더가 갈리고, 2층이 1층 앵커에 합쳐 쓰지 못한다.
        // ★ 자세 매핑이 빠지면 후처리가 1층 기본값으로 되돌아가 2층 reply·wake_up 을 앉기·눕기로 맞춘다.
        verify(post, times(1)).open("images/zzal/pets/7/basic/2", "v1");
        InOrder order = inOrder(session);
        order.verify(session).split(eq("images/zzal/pets/7/grid.png"),
                anyList(), eq(postures.forStep("v1", GridStep.NAME)));
        order.verify(session).split(eq("images/zzal/pets/7/grid2.png"),
                anyList(), eq(postures.forStep("v1", PostProcessStep.GRID2)));
        order.verify(session).close();
        verify(recorder).markBasicBaked(7L, 2);
    }

    @Test
    @DisplayName("★★ 실패 주입 — 설정에 모르는 버전이 적히면 기동에서 막는다(폴백 없음)")
    void unknownConfiguredVersionBlocksBoot() {
        // 전에는 프롬프트가 없으면 조용히 옛 버전으로 내려갔다. 그러면 설정은 새것인데 그림은 옛것이
        // 구워지고, 오류도 404 도 없이 화면을 봐야만 드러난다. 뜨지 않는 편이 낫다.
        assertThatThrownBy(() -> new PipelineRegistry(StepMocks.sheet(), StepMocks.identity(),
                StepMocks.grid(), StepMocks.grid2(), StepMocks.post(),
                mock(MotionGridStep.class), mock(MotionPostStep.class), "없는버전", "v1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.zzal.pipeline-version")
                .hasMessageContaining("없는버전");
    }

    @Test
    @DisplayName("★ 실패 주입 — 2층 격자가 없으면 반쪽으로 굽지 않고 멈춘다")
    void missingSecondGridStopsInsteadOfBakingHalf() throws Exception {
        // ★ 1층만 잘라 성공으로 치면 16칸 중 8칸이 빈 펫이 <b>완성</b>으로 기록된다. 오류는 어디에서도
        //   안 나고 화면의 여덟 칸이 비어야만 드러난다 — 그럴 바엔 여기서 크게 실패하는 편이 낫다.
        PostProcessor post = mock(PostProcessor.class);
        GenerationRecorder recorder = mock(GenerationRecorder.class);
        PostProcessStep step =
                new PostProcessStep(post, new MotionCatalog("", "", "v1"), new HatchPostures(), recorder);

        StepContext ctx = new StepContext(7L, "여울", null, "v1");
        ctx.putImage(GridStep.NAME, "images/zzal/pets/7/grid.png");

        assertThatThrownBy(() -> step.run(ctx))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(PostProcessStep.GRID2);
        // 판 번호도 올리지 않는다 — 올려 두면 굽지도 않은 판이 주소로 나간다.
        verify(recorder, never()).nextBasicRound(anyLong());
        verify(recorder, never()).markBasicBaked(anyLong(), anyInt());
    }

    @Test
    @DisplayName("★ 2층 프롬프트 — GRID_SPEC 이 게이트 사양과 같고 {IDENT} 자리가 있다")
    void grid2PromptIsWellFormed() throws Exception {
        String prompt = resource("zzal/prompt/v1/grid2.txt");
        String spec = resource("zzal/pipeline/v1/grid_spec.txt");

        Matcher m = Pattern.compile("^#\\s*GRID_SPEC:.*$", Pattern.MULTILINE).matcher(prompt);
        assertThat(m.find()).as("2층 격자도 같은 게이트를 탄다").isTrue();
        assertThat(spec.trim()).isEqualTo(m.group().trim());
        // 이 자리가 없으면 정체성 문단이 프롬프트에 안 들어가고, 그림은 남의 캐릭터가 된다.
        assertThat(prompt).contains("{IDENT}");
    }

    @Test
    @DisplayName("★ 자세 매핑 — 1층은 확정된 그 조합(sick 웅크림·sleep 눕기), 2층은 wash 만 웅크림")
    void posturesMatchTheJudgedCombination() {
        HatchPostures postures = new HatchPostures();

        assertThat(split(postures.forStep("v1", GridStep.NAME)))
                .as("1층 확정본(v02)을 만든 그 매핑 — state8_v5.DEFAULT_POSTURE 와 같다")
                .containsExactly("base=standing", "eat=standing", "joy=standing", "sad=standing",
                        "sick=crouch", "pet=standing", "hello=standing", "sleep=lying");

        List<String> layer2 = split(postures.forStep("v1", PostProcessStep.GRID2));
        assertThat(layer2.stream().map(e -> e.split("=")[0]).toList())
                .as("이름·순서가 2층 기준 key 와 같아야 --keys 와 짝이 맞는다")
                .containsExactlyElementsOf(LAYER2);
        assertThat(layer2.stream().filter(e -> !e.endsWith("=standing")).toList())
                .as("2층에서 서 있지 않은 칸은 목욕 하나뿐이다(눕는 칸은 없다)")
                .containsExactly("wash=crouch");
    }

    @Test
    @DisplayName("★ 매핑을 빠뜨리면 어느 파일의 무엇이 없는지 말하며 멈춘다")
    void missingPostureMappingSaysWhichFile() {
        HatchPostures postures = new HatchPostures();

        assertThatThrownBy(() -> postures.forStep("v1", "grid3"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("zzal/pipeline/v1/postures.txt")
                .hasMessageContaining("grid3");

        // 매핑 파일이 없는 버전은 빈 값이 정상이다 — 그 버전의 후처리 스크립트는 --postures 를 모른다.
        assertThat(postures.forStep("없는버전", GridStep.NAME)).isEmpty();
    }

    @Test
    @DisplayName("★ 설정 hatch.states.v1 는 1층 8 + 2층 8 = 16종(다른 갈래가 yml 에 적는다)")
    void configuredStatesAreSixteen() {
        List<String> all = Stream.concat(LAYER1.stream(), LAYER2.stream()).toList();
        assertThat(all).hasSize(16).doesNotHaveDuplicates();
    }

    private static List<String> split(String spec) {
        return java.util.Arrays.stream(spec.split(",")).map(String::trim).filter(t -> !t.isBlank()).toList();
    }

    @Test
    @DisplayName("★ 게이트 사양 파일이 실제 프롬프트의 GRID_SPEC 과 글자까지 같다")
    void gateSpecMatchesPrompt() throws Exception {
        String prompt = resource("zzal/prompt/v1/grid.txt");
        String spec = resource("zzal/pipeline/v1/grid_spec.txt");

        Matcher m = Pattern.compile("^#\\s*GRID_SPEC:.*$", Pattern.MULTILINE).matcher(prompt);
        assertThat(m.find())
                .as("prompt/v1/grid.txt 에 GRID_SPEC 줄이 있어야 게이트가 무엇을 검사할지 안다")
                .isTrue();
        // 어긋나면 게이트가 옛 형식으로 멀쩡한 격자를 버리거나, 깨진 격자를 통과시킨다.
        // 둘 다 로그를 봐도 안 보이므로 여기서 빌드를 깬다.
        assertThat(spec.trim()).isEqualTo(m.group().trim());
    }

    @Test
    @DisplayName("★ 스크립트가 찍는 표식과 자바가 찾는 표식이 같다")
    void gateMarkerMatches() throws Exception {
        String script = resource("zzal/pipeline/v1/service_post.py");
        assertThat(script)
                .as("service_post.py 가 이 표식을 찍어야 자바가 '격자를 다시 구워라' 로 알아듣는다")
                .contains("GRID_STRUCTURE_MARK = \"" + GenerationRunner.GRID_STRUCTURE_MARK + "\"");
    }

    @Test
    @DisplayName("★ 후처리가 내놓는 파일 이름 8종 = 설정에 적어야 하는 hatch.states.v1")
    void postProcessKeysMatchConfiguredStates() throws Exception {
        String script = resource("zzal/pipeline/v1/state8_v5.py");

        Matcher m = Pattern.compile("^KEYS\\s*=\\s*\\[(.*?)]", Pattern.MULTILINE).matcher(script);
        assertThat(m.find()).as("state8_v5.py 의 KEYS 가 파일 이름의 유일한 출처다").isTrue();
        List<String> keys = java.util.Arrays.stream(m.group(1).split(","))
                .map(s -> s.trim().replaceAll("^[\"']|[\"']$", ""))
                .filter(s -> !s.isBlank())
                .toList();

        // 이름이 하나라도 어긋나면 후처리는 성공하는데 자바가 "후처리 결과가 없습니다" 로 죽는다.
        assertThat(keys).containsExactlyElementsOf(LAYER1);
    }

    @Test
    @DisplayName("★ 실패 주입 — 게이트가 막으면 '격자를 버리라'는 신호가 붙어 돌아온다")
    void gateFailureAsksForANewGrid() {
        GenerationRecorder recorder = mock(GenerationRecorder.class);
        GenerationRunner runner = new GenerationRunner(recorder, mock(ZzalAlerts.class));

        GenerationStep gate = new GenerationStep() {
            @Override
            public String name() {
                return PostProcessStep.NAME;
            }

            @Override
            public int limitSeconds() {
                return 30;
            }

            @Override
            public String label() {
                return "깨어날 준비를 하는 중";
            }

            @Override
            public StepResult run(StepContext ctx) {
                throw new IllegalStateException(
                        "후처리 실패(exit 3)\n[게이트] " + GenerationRunner.GRID_STRUCTURE_MARK
                                + " — 구조이상: 열이 5개가 아님 (6개)");
            }
        };

        StepContext ctx = new StepContext(7L, "여울", null, "v1");
        RunResult r = runner.run(1L, ctx, List.of(List.of(gate)), List.of());

        assertThat(r.success()).isFalse();
        assertThat(r.gridRejected())
                .as("이 신호가 없으면 재시도가 **바로 그 깨진 격자**를 다시 자른다")
                .isTrue();
        assertThat(r.costUsd()).isEqualTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("표식이 없는 평범한 실패는 격자를 버리지 않는다")
    void ordinaryFailureKeepsTheGrid() {
        GenerationRunner runner = new GenerationRunner(mock(GenerationRecorder.class), mock(ZzalAlerts.class));

        GenerationStep boom = new GenerationStep() {
            @Override
            public String name() {
                return PostProcessStep.NAME;
            }

            @Override
            public int limitSeconds() {
                return 30;
            }

            @Override
            public String label() {
                return "깨어날 준비를 하는 중";
            }

            @Override
            public StepResult run(StepContext ctx) {
                throw new IllegalStateException("후처리 결과가 없습니다: base.webp");
            }
        };

        RunResult r = runner.run(1L, new StepContext(7L, "여울", null, "v1"), List.of(List.of(boom)), List.of());
        assertThat(r.success()).isFalse();
        assertThat(r.gridRejected()).isFalse();
    }
}
