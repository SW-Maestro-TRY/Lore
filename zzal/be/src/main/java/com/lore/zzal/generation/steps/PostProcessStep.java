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
 * 1층·2층을 <b>연달아</b> 후처리하고, 2층이 1층이 남긴 {@code anchors.json} 에 합쳐 쓴다.
 * 두 호출이 같은 폴더를 봐야 하는데, 전에는 자르는 쪽이 자기 안에서 폴더를 만들고 지워서
 * <b>1층이 지운 폴더를 2층이 보게</b> 됐다. 그래서 세션을 열어 두 호출을 감싸고, 닫는 자리에서
 * 앵커를 한 번 올린다.
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
     * 두 층을 잘라 {@code basic/{판}/} 아래에 놓는다.
     *
     * <h3>★★ 버전으로 가르는 갈래를 두지 않는다</h3>
     * 전에는 "이 버전은 옛 자리에 떨어뜨린다" · "이 버전만 자세 매핑을 넘긴다" 를 <b>버전 이름을 담은
     * 집합</b>으로 갈랐다. 그 집합은 버전 이름이 바뀌어도 아무 소리를 안 내고 낡는다 — 이름은 새것인데
     * 갈래는 옛것이 되고, 그건 화면을 봐야만 드러난다. 갈래를 없애면 낡을 것도 없다.
     * 자세 매핑이 없는 버전은 {@link HatchPostures} 가 빈 문자열을 주고, 후처리가 그 인자를 안 넘긴다.
     *
     * <h3>★ 2층 격자가 없으면 <b>멈춘다</b></h3>
     * 부화는 {@code [grid, grid2]} 를 한 묶음으로 굽고, 재시도도 앞선 성공분을 그대로 이어받는다.
     * 그러니 여기서 2층이 비는 것은 "굽다 만 것" 이 아니라 <b>설명이 안 되는 상태</b>다.
     * 1층만 잘라 성공으로 치면 16칸 중 8칸이 없는 펫이 완성으로 기록되고, 화면은 그 여덟 칸에
     * 빈 그림을 그린다. 오류는 어디에서도 안 난다.
     */
    @Override
    public StepResult run(StepContext ctx) throws Exception {
        String grid2 = ctx.image(GRID2);
        if (grid2 == null) {
            throw new IllegalStateException(
                    "2층 격자(%s)가 없습니다 — 1층만 자르면 16칸 중 8칸이 빈 채로 완성이 됩니다".formatted(GRID2));
        }
        // ★ 판 번호는 자르기 <b>전에</b> 정한다 — 그 값이 곧 올릴 주소다.
        int round = recorder.nextBasicRound(ctx.petId());
        String prefix = MotionImageKeys.basicPrefix(ctx.petId(), round);

        try (PostProcessor.Session session = postProcessor.open(prefix, ctx.version())) {
            // ★ 같은 세션·같은 스레드에서 순차로 돈다 — 2층이 1층 앵커에 합쳐 쓴다.
            session.split(ctx.image(GridStep.NAME), keysOf(MotionLayer.BASIC_1),
                    postures.forStep(ctx.version(), GridStep.NAME));
            session.split(grid2, keysOf(MotionLayer.BASIC_2),
                    postures.forStep(ctx.version(), GRID2));
        }
        // ★ 세션이 닫히고(앵커까지 올라가고) 나서 판을 올린다. 먼저 올리면 앵커가 빠진 판을
        //   화면이 먼저 받는다.
        recorder.markBasicBaked(ctx.petId(), round);
        return StepResult.free(NAME);
    }

    /** 두 번째 격자(2층) 단계의 이름. */
    public static final String GRID2 = "grid2";

    private java.util.List<String> keysOf(MotionLayer layer) {
        return catalog.basic().stream().filter(m -> m.layer() == layer).map(MotionSpec::key).toList();
    }
}
