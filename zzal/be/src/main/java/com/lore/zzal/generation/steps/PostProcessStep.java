package com.lore.zzal.generation.steps;

import com.lore.zzal.generation.GenerationRecorder;
import com.lore.zzal.generation.GenerationStep;
import com.lore.zzal.generation.HatchPostures;
import com.lore.zzal.generation.StepContext;
import com.lore.zzal.generation.StepResult;
import com.lore.zzal.generation.client.PostProcessor;
import com.lore.zzal.motion.MotionCatalog;
import com.lore.zzal.motion.MotionImageKeys;
import com.lore.zzal.motion.MotionLayer;
import com.lore.zzal.motion.MotionSpec;
import org.springframework.stereotype.Component;

/**
 * 4단계 — 격자 한 장을 잘라 8개 움짤로 만든다. **우리 계산이라 돈이 안 든다.**
 *
 * 하는 일 — 초록 배경 제거 · 16칸 절단 · 이물질 제거 · 발 높이 정렬 · 투명 배경 조립.
 * 이 로직은 실험에서 여러 사고를 잡아 가며 다듬은 것이라(마젠타 격자점·땀 오인식·
 * 머리 틈 초록 잔여) 자바로 다시 쓰지 않고 **검증된 파이썬을 그대로 실행**한다.
 *
 * 실측 1~2초.
 *
 * <h3>★★ 작업 폴더의 수명을 여기가 쥔다</h3>
 * v4 는 1층·2층을 <b>연달아</b> 후처리하고, 2층이 1층이 남긴 {@code anchors.json} 에 합쳐 쓴다.
 * 두 호출이 같은 폴더를 봐야 하는데, 전에는 자르는 쪽이 자기 안에서 폴더를 만들고 지워서
 * <b>1층이 지운 폴더를 2층이 보게</b> 됐다. 그래서 세션을 열어 두 호출을 감싸고, 닫는 자리에서
 * 앵커를 한 번 올린다. 1장짜리 경로(v1·v2)도 같은 세션 안에서 1회로 돈다 — 갈래마다 수명을
 * 다르게 두면 그 갈래만 조용히 다르게 동작한다.
 */
@Component
public class PostProcessStep implements GenerationStep {

    public static final String NAME = "postprocess";

    private final PostProcessor postProcessor;
    private final MotionCatalog catalog;
    private final HatchPostures postures;
    private final GenerationRecorder recorder;

    public PostProcessStep(PostProcessor postProcessor, MotionCatalog catalog, HatchPostures postures,
                           GenerationRecorder recorder) {
        this.postProcessor = postProcessor;
        this.catalog = catalog;
        this.postures = postures;
        this.recorder = recorder;
    }

    @Override
    public String name() {
        return NAME;
    }

    /** 우리 서버 안 계산이라 늘어질 이유가 없다. */
    @Override
    public int limitSeconds() {
        return 30;
    }

    @Override
    public String label() {
        return "깨어날 준비를 하는 중";
    }

    /**
     * 격자가 한 장뿐인 판(2층 프롬프트가 없던 때의 v4·중간에 멈췄다 이어 도는 job)을 위해 남겨 둔다.
     * 출력은 <b>16종과 같은 자리</b>(basic/)에 둔다.
     *
     * ★ 왜 v1 과 자리가 다른가 — 화면이 기본 행동을 {@code .../basic/{판}/{key}.webp} 로 조립한다(api-v2.md 2절).
     *   v1 은 그 규약 이전의 8상태라 한 단 위에 떨어뜨리지만, v4 의 8종은 <b>16종의 앞 절반</b>이다.
     *   같은 자리에 놓아야 2층이 확정돼 붙을 때 앞 절반을 다시 굽지 않아도 된다.
     * ★ 이름은 카탈로그가 아니라 설정({@code app.zzal.hatch.states.v4})에서 온다 — 1층 8종의 key 정리가
     *   아직 진행 중이라, 카탈로그를 여기서 같이 건드리면 두 곳이 서로를 기다리게 된다.
     */
    private static final java.util.Set<String> LAYER1_ONLY_TO_BASIC = java.util.Set.of("v4");

    /**
     * 칸의 자세 유형을 후처리에 넘기는 버전.
     *
     * ★ v4 의 후처리(state8_v5)는 서 있는 칸을 <b>발</b> 기준으로, 앉은·누운 칸을 <b>본체</b> 기준으로
     *   맞춘다. 어느 칸이 어느 자세인지는 층마다 다르다 — 1층은 5번 sick·8번 sleep, 2층은 4번 wash.
     *   안 넘기면 파이썬이 1층 기본값으로 되돌아가 <b>2층의 reply·wake_up 을 앉기·눕기로</b> 맞춘다.
     * ★ v1·v2 의 스크립트는 이 인자를 모른다 — 그래서 버전으로 가른다.
     */
    private static final java.util.Set<String> POSTURE_AWARE = java.util.Set.of("v4");

    @Override
    public StepResult run(StepContext ctx) throws Exception {
        String grid2 = ctx.image(GRID2);
        // ★ 판 번호는 자르기 <b>전에</b> 정한다 — 그 값이 곧 올릴 주소다.
        int round = recorder.nextBasicRound(ctx.petId());
        boolean legacyLayout = grid2 == null && !LAYER1_ONLY_TO_BASIC.contains(ctx.version());
        // v1 은 basic/ 규약 이전이라 판 칸이 없다. 그 버전은 더 굽지 않으므로 덮어쓸 일도 없다.
        String prefix = legacyLayout
                ? MotionImageKeys.legacyStatePrefix(ctx.petId())
                : MotionImageKeys.basicPrefix(ctx.petId(), round);

        try (PostProcessor.Session session = postProcessor.open(prefix, ctx.version())) {
            if (grid2 == null) {
                if (legacyLayout) {
                    // v1 — 격자 1장 → 8상태(idle·eat·…). 출력 이름은 설정 hatch.states.v1.
                    session.split(ctx.image(GridStep.NAME));
                    return StepResult.free(NAME);
                }
                // v4 — 1층 격자 1장 → 기본 행동 8종. 출력 이름은 설정 hatch.states.v4.
                session.split(ctx.image(GridStep.NAME));
            } else if (POSTURE_AWARE.contains(ctx.version())) {
                // v4 — 격자 2장. 칸의 자세 유형까지 넘긴다(pipeline/v4/postures.txt).
                // ★ 같은 세션·같은 스레드에서 순차로 돈다 — 2층이 1층 앵커에 합쳐 쓴다.
                session.split(ctx.image(GridStep.NAME), keysOf(MotionLayer.BASIC_1),
                        postures.forStep(ctx.version(), GridStep.NAME));
                session.split(grid2, keysOf(MotionLayer.BASIC_2),
                        postures.forStep(ctx.version(), GRID2));
            } else {
                // v2 — 그 버전의 스크립트는 --postures 를 모른다.
                session.split(ctx.image(GridStep.NAME), keysOf(MotionLayer.BASIC_1));
                session.split(grid2, keysOf(MotionLayer.BASIC_2));
            }
        }
        // ★ 세션이 닫히고(앵커까지 올라가고) 나서 판을 올린다. 먼저 올리면 앵커가 빠진 판을
        //   화면이 먼저 받는다.
        recorder.markBasicBaked(ctx.petId(), round);
        return StepResult.free(NAME);
    }

    /** v2 두 번째 격자의 단계 이름. */
    public static final String GRID2 = "grid2";

    private java.util.List<String> keysOf(MotionLayer layer) {
        return catalog.basic().stream().filter(m -> m.layer() == layer).map(MotionSpec::key).toList();
    }
}
