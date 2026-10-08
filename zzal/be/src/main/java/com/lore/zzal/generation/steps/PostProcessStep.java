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
 * <h3>★★ 1층만 자른다(#696)</h3>
 * 전에는 1층·2층을 한 세션에서 연달아 잘랐다. 이제 부화는 1층에서 끝나고, 2층은 부화 뒤
 * {@link Layer2PostStep} 이 이 단계가 올린 {@code anchors.json} 을 내려받아 같은 방식으로 자른다.
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

    /**
     * 파이썬 계산(numpy/scipy)만 보면 1~2초지만, 이 단계는 <b>S3에서 격자를 받고 · 층마다 webp 8개를
     * 한 개씩 올리는 왕복(2층이면 격자 2 다운 + webp 16 업로드) · 스크립트 추출 · 파이썬 2회 실행</b>을
     * 모두 포함한다. 작은 t3.small 운영 서버에서는 이 왕복 오버헤드로 <b>실측 20~30초</b>가 나오고,
     * 무거운 이미지는 옛 30초 값을 넘겨 <b>4/5(후처리)에서 TIMEOUT</b> 으로 멈췄다(2026-09-17 dev 실측).
     * 그래서 넉넉히 120초로 둔다. (근본 최적화 — webp 병렬 업로드·스크립트 추출 재사용 — 은 별도 과제.)
     */
    @Override
    public int limitSeconds() {
        return 120;
    }

    @Override
    public String label() {
        return "깨어날 준비를 하는 중";
    }

    /**
     * 1층 격자를 잘라 {@code basic/{판}/} 아래에 놓는다(#696 — 부화는 1층에서 끝난다).
     *
     * <h3>★ 2층은 여기서 안 자른다</h3>
     * 2층은 부화가 끝난 뒤 별도 작업({@link Layer2PostStep})이 굽고 자른다. 그 작업이 이 단계가 올린
     * {@code anchors.json}(1층 K·Hw)을 내려받아 같은 스크립트로 2층을 자르므로, 결과 바이트는 전과 같다.
     *
     * <h3>★★ 버전으로 가르는 갈래를 두지 않는다</h3>
     * 자세 매핑이 없는 버전은 {@link HatchPostures} 가 빈 문자열을 주고, 후처리가 그 인자를 안 넘긴다.
     */
    @Override
    public StepResult run(StepContext ctx) throws Exception {
        // ★ 판 번호는 자르기 <b>전에</b> 정한다 — 그 값이 곧 올릴 주소다.
        int round = recorder.nextBasicRound(ctx.petId());
        String prefix = MotionImageKeys.basicPrefix(ctx.petId(), round);

        try (PostProcessor.Session session = postProcessor.open(prefix, ctx.version())) {
            splitTagged(session, GridStep.NAME, ctx.image(GridStep.NAME), MotionLayer.BASIC_1, ctx.version(),
                    keysOf(catalog, MotionLayer.BASIC_1), postures);
        }
        // ★ 세션이 닫히고(앵커까지 올라가고) 나서 판을 올린다. 먼저 올리면 앵커가 빠진 판을
        //   화면이 먼저 받는다.
        recorder.markBasicBaked(ctx.petId(), round);
        return StepResult.free(NAME);
    }

    /**
     * 한 층을 자르고, 실패하면 <b>어느 격자였는지</b>를 메시지 앞에 붙여 올린다({@code [격자=grid2]}).
     *
     * <h3>★ 왜 (2026-10-07)</h3>
     * 재시도는 실패한 격자만 버리고 다시 굽는다. 전에는 어느 쪽이 깨졌는지 몰라 <b>둘 다</b> 버렸고,
     * 멀쩡한 쪽까지 다시 구워 돈과 시간을 두 배로 썼다. 이 표식은 {@code GenerationRunner} 가 읽는다.
     * ★ 원래 메시지는 그대로 뒤에 붙인다 — 게이트 표식·한도(429) 판정이 그 글자를 본다.
     */
    public static void splitTagged(PostProcessor.Session session, String gridStep, String gridKey,
                            MotionLayer layer, String version, java.util.List<String> keys,
                            HatchPostures postures) throws Exception {
        try {
            session.split(gridKey, keys, postures.forStep(version, gridStep), layerNumber(layer));
        } catch (InterruptedException e) {
            throw e;                    // 시간 초과로 끊긴 것 — 그대로 올린다
        } catch (Exception e) {
            throw new IllegalStateException(GRID_SOURCE_FORMAT.formatted(gridStep) + " " + e.getMessage(), e);
        }
    }

    /** 실패 메시지 앞에 붙는 격자 이름 표식. {@code GenerationRunner#failedGrid} 와 짝이다. */
    public static final String GRID_SOURCE_FORMAT = "[격자=%s]";

    /** 로그·작업 파일 이름에 쓰는 층 번호. 1층=1 · 2층=2 · 선물=3. */
    static int layerNumber(MotionLayer layer) {
        return layer == null ? 1 : layer.ordinal() + 1;
    }

    /** 두 번째 격자(2층) 단계의 이름. */
    public static final String GRID2 = "grid2";

    /** 그 층의 카탈로그 key 8개(격자 칸 순서). */
    public static java.util.List<String> keysOf(MotionCatalog catalog, MotionLayer layer) {
        return catalog.basic().stream().filter(m -> m.layer() == layer).map(MotionSpec::key).toList();
    }
}
