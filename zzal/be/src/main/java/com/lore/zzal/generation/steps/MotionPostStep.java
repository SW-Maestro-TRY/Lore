package com.lore.zzal.generation.steps;

import com.lore.zzal.generation.GenerationStep;
import com.lore.zzal.generation.MotionPostProfiles;
import com.lore.zzal.generation.StepContext;
import com.lore.zzal.generation.StepResult;
import com.lore.zzal.generation.client.MotionPostProcessor;

/**
 * 모션 2단계 — 16프레임 격자를 잘라 움짤 하나로 만든다. <b>우리 계산이라 돈이 안 든다.</b>
 *
 * 하는 일은 부화 후처리와 거의 같다(초록 배경 제거 · 절단 · 이물질 제거 · 정렬).
 * 다른 것은 정렬 방식뿐이고, 그 이유는 {@link MotionPostProcessor} 에 적어 두었다.
 *
 * <h3>★ 정렬 기준이 동작마다 다르다</h3>
 * 구르기는 발, 뒤로넘어짐은 접지앵커(엉덩이·등이 닿는 자리)로 확정됐다. 어느 쪽인지는
 * 코드가 아니라 {@link MotionPostProfiles} 의 표가 정한다 — 표에 없는 동작은 굽지 않는다.
 */
public class MotionPostStep implements GenerationStep {

    public static final String NAME = "post16";

    /** 앞에서 넘겨받는 이름표 — 어느 동작을 굽고 있나(후처리 프로파일을 고르는 데 쓴다). */
    public static final String MOTION_KEY_IN = "motionKey";

    /** 캔버스 크기를 글로 적는 형식. {@code "295x321"} */
    private static String size(MotionPostProcessor.Built built) {
        return "%dx%d".formatted(built.width(), built.height());
    }

    /**
     * {@code "295x321"} → {@code [295, 321]}. 모양이 아니면 {@code null} — <b>추측하지 않는다.</b>
     *
     * ★ 크기를 모르는 것과 0 은 다르다. 못 읽었을 때 0 을 주면 화면이 그 값을 믿고 0px 로 그린다.
     */
    public static int[] parseSize(String text) {
        if (text == null) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\d+)x(\\d+)$").matcher(text.trim());
        return m.matches()
                ? new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))}
                : null;
    }

    private final MotionPostProcessor postProcessor;
    private final MotionPostProfiles profiles;

    public MotionPostStep(MotionPostProcessor postProcessor, MotionPostProfiles profiles) {
        this.postProcessor = postProcessor;
        this.profiles = profiles;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int limitSeconds() {
        return 60;
    }

    @Override
    public String label() {
        return "움직임을 다듬는 중";
    }

    @Override
    public StepResult run(StepContext ctx) throws Exception {
        String motionKey = ctx.text(MOTION_KEY_IN);
        if (motionKey == null || motionKey.isBlank()) {
            // 어느 동작인지 모르면 어떤 기준으로 칸을 맞출지도 모른다. 아무거나 골라 굽지 않는다.
            throw new IllegalStateException("어느 동작을 굽는지가 안 넘어왔습니다 — petId=" + ctx.petId());
        }
        String profile = profiles.forMotion(ctx.version(), motionKey);
        MotionPostProcessor.Built built =
                postProcessor.build(ctx.image(MotionGridStep.NAME), ctx.outputPrefix(), profile);
        // ★ 완성된 움짤의 키를 결과로 돌려준다. 이걸 모션 행에 적어야 화면이 그림을 찾는다.
        // ★ 캔버스 크기는 글 자리에 같이 싣는다 — 이어받기(재개)가 이 단계를 건너뛸 때도
        //   크기가 따라오게 하려면 <b>단계 기록에 남는 값</b>이어야 한다. 키만 남기면 이어받은
        //   판에서만 크기가 null 이 되고, 그건 그 경로를 타 봐야만 드러난다.
        return new StepResult(NAME, built.imageKey(), size(built), "none", java.math.BigDecimal.ZERO);
    }
}
