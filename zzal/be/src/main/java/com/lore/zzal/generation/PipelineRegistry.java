package com.lore.zzal.generation;

import com.lore.zzal.generation.steps.GridStep;
import com.lore.zzal.generation.steps.IdentityStep;
import com.lore.zzal.generation.steps.PostProcessStep;
import com.lore.zzal.generation.steps.MotionGridStep;
import com.lore.zzal.generation.steps.MotionPostStep;
import com.lore.zzal.generation.steps.SheetStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 버전 → 단계 목록. <b>단계는 묶음(stage) 단위로 돈다.</b>
 *
 * <h3>★ 묶음이 곧 "나란히 돌려도 되는가"의 선언이다</h3>
 * 한 묶음 안의 단계들은 <b>동시에</b> 돌고, 묶음과 묶음 사이는 순서대로다.
 * 부화의 {@code [grid, grid2]} 가 그 예다 — 두 장은 서로를 안 보고 둘 다 {@code identity} 하나만 쓰므로
 * 겹쳐 구우면 한 장 값(실측 41초)이 통째로 빠진다.
 *
 * ★ 나란히 돌 수 있는지를 단계가 스스로 말하게 하지 않은 이유 — 그러면 기본값이 필요하고,
 *   새 단계를 만들며 그 선언을 빠뜨리면 <b>엉뚱한 것이 같이 돌아도 아무도 모른다.</b>
 *   파이프라인의 모양은 여기 한 곳에 있으므로, 묶음도 여기서 눈으로 보이게 둔다.
 *
 * ★ 파이프라인이 계속 바뀔 예정이라(2026-09-02 상훈님 확인) 단계 구성을 여기 한 곳에 모은다.
 *
 *   단계 제거   그 버전의 목록에서 빼기만 한다. 클래스는 남겨 옛 펫을 계속 설명한다
 *   단계 추가   새 Step 클래스를 만들고 목록에 넣는다. 실행기는 안 바뀐다
 *   되돌리기    설정(app.zzal.pipeline-version)을 옛 버전으로
 *
 * 예) 정체성 문단을 없앤 다음 판은 이렇게 된다
 *     "v2", List.of(sheet, grid, post)
 *
 * ★★ 버전 축이 <b>종류마다 따로</b>다. 부화 v1 과 모션 v1 은 이름만 같을 뿐 다른 것이고,
 *    각각 독립적으로 올라간다. 한 축에 몰아 두면 모션 프롬프트를 고쳐 올렸을 뿐인데
 *    부화 기록의 버전 번호까지 뛰어, 나중에 "이 펫은 어떤 조합으로 구워졌나" 가 흐려진다.
 */
@Component
public class PipelineRegistry {

    /**
     * 버전마다 <b>정체성 문단을 재료로 쓰는 단계 이름</b>. 거부 재시도에서 함께 폐기한다.
     * 단계 구성({@link #versions})과 같은 파일에 두어 한쪽만 고치는 일이 없게 한다.
     */
    private static final Map<GenKind, Map<String, List<String>>> IDENTITY_DEPENDENTS = Map.of(
            GenKind.HATCH, Map.of(
                    "v1", List.of(IdentityStep.NAME, GridStep.NAME, PostProcessStep.GRID2)),
            GenKind.MOTION, Map.of("v1", List.of()));

    private final Map<GenKind, Map<String, List<List<GenerationStep>>>> versions;
    private final Map<GenKind, String> currentVersions;

    private static final Logger log = LoggerFactory.getLogger(PipelineRegistry.class);

    @Autowired
    public PipelineRegistry(SheetStep sheet, IdentityStep identity,
                            @Qualifier("gridStep") GridStep grid, @Qualifier("grid2Step") GridStep grid2,
                            PostProcessStep post,
                            MotionGridStep motionGrid, MotionPostStep motionPost,
                            @Value("${app.zzal.pipeline-version:v1}") String hatchVersion,
                            @Value("${app.zzal.motion-pipeline-version:v1}") String motionVersion) {
        this.versions = Map.of(
                GenKind.HATCH, Map.of(
                        // v1 = 격자 2장(1층·2층) → 기본 행동 16종. 1층·2층 모두 검수를 마친 확정 조합이다.
                        //   프롬프트 prompt/v1/{sheet,identity,grid,grid2}.txt,
                        //   후처리 pipeline/v1/service_post.py(state8_v5 + 격자 게이트 + 칸별 자세 매핑).
                        // ★ [grid, grid2] 가 한 묶음 = 나란히 굽는다 — 두 격자는 서로를 안 보고
                        //   identity 하나만 쓰므로 겹쳐 구우면 한 장 값(실측 41초)이 통째로 빠진다.
                        //   identity 는 앞 묶음이라 반드시 먼저 끝난다.
                        "v1", List.of(List.of(sheet), List.of(identity), List.of(grid, grid2), List.of(post))),
                GenKind.MOTION, Map.of("v1", List.of(List.of(motionGrid), List.of(motionPost))));
        this.currentVersions = Map.of(GenKind.HATCH, hatchVersion, GenKind.MOTION, motionVersion);
        verifyIdentityDependents();
        verifyCurrentVersions();
        // ★★ 설정이 안 먹었을 때 조용히 옛 값으로 도는 것을 막는다 — 어느 버전으로 굽는지는
        //    그림을 열어 봐야만 드러나므로, 기동 로그가 그것을 먼저 말해야 한다.
        log.info("파이프라인 버전 — 부화={} (app.zzal.pipeline-version) · 모션={} (app.zzal.motion-pipeline-version)",
                currentVersions.get(GenKind.HATCH), currentVersions.get(GenKind.MOTION));
    }

    /**
     * 설정에 적힌 버전이 <b>실제로 있는 버전인지</b> 기동할 때 확인한다.
     *
     * ★★ 폴백을 두지 않는다. 전에는 프롬프트가 없으면 조용히 옛 버전으로 내려갔는데, 그러면
     *   <b>설정은 새 버전인데 실제로는 옛 그림이 구워진다.</b> 오류도 404 도 안 나고 화면을 봐야만
     *   드러나는 종류라, 뜨지 않는 편이 낫다. 무엇을 고쳐야 하는지는 예외가 설정 이름으로 말한다.
     */
    private void verifyCurrentVersions() {
        currentVersions.forEach((kind, version) -> {
            Map<String, List<List<GenerationStep>>> known = versions.getOrDefault(kind, Map.of());
            if (!known.containsKey(version)) {
                String property = kind == GenKind.HATCH
                        ? "app.zzal.pipeline-version" : "app.zzal.motion-pipeline-version";
                throw new IllegalStateException(
                        "%s 에 모르는 파이프라인 버전이 적혀 있습니다: %s (가능한 값: %s)"
                                .formatted(property, version, known.keySet()));
            }
        });
    }

    /** 돌릴 묶음들. 묶음 안은 동시에, 묶음 사이는 순서대로. */
    public List<List<GenerationStep>> stages(GenKind kind, String version) {
        List<List<GenerationStep>> stages = versions.getOrDefault(kind, Map.of()).get(version);
        if (stages == null) {
            // 없는 버전으로 굽기 시작하면 조용히 기본값으로 가지 않는다 — 그러면 기록에는
            // v9 라고 남고 실제로는 v1 로 구워진, 설명이 안 되는 결과가 생긴다.
            throw new IllegalArgumentException(
                    "모르는 파이프라인 버전입니다: %s %s".formatted(kind, version));
        }
        return stages;
    }

    /** 묶음을 펼친 목록 — 몇 단계인지 세거나 순서를 볼 때. 돌릴 때는 {@link #stages} 를 쓴다. */
    public List<GenerationStep> steps(GenKind kind, String version) {
        return stages(kind, version).stream().flatMap(List::stream).toList();
    }

    /**
     * <b>정체성 문단을 재료로 쓰는 단계들</b> — 거부(MODERATION)로 문단을 새로 만들 때 함께 폐기할 목록.
     *
     * <h3>★★ 왜 문단만 지우면 안 되나</h3>
     * 재시도는 <b>성공한 단계를 건너뛴다.</b> 그래서 문단만 지우면 1층 격자는 <b>옛 문단</b>으로 구운
     * 그림을 그대로 쓰고, 2층 격자만 <b>새 문단</b>으로 구워진다. 같은 아이인데 두 격자의 묘사 근거가
     * 달라져, 1층과 2층의 생김새가 어긋난 채로 사용자에게 간다.
     *
     * <h3>★ 왜 단계가 스스로 말하게 하지 않나</h3>
     * 단계마다 "나는 문단을 쓴다" 를 선언하게 하면 <b>기본값이 필요하고, 새 단계가 그 선언을 빠뜨리면
     * 조용히 틀린다.</b> 어긋난 격자는 예외를 내지 않으므로 아무도 모른 채 배포된다.
     * 파이프라인의 모양이 이 파일 한 곳에 있으므로, 이 의존도 바로 옆에 적어 둔다.
     *
     * ⚠️ 새 단계가 {@code ctx.text(IdentityStep.NAME)} 을 읽는다면 <b>{@link #IDENTITY_DEPENDENTS} 에
     *    이름을 더해야 한다.</b> 후처리는 여기 없다 — 격자 <b>그림</b>만 보고, 앞 단계가 실패하면
     *    애초에 도달하지 못해 성공 기록이 남지 않는다.
     */
    public List<String> identityDependents(GenKind kind, String version) {
        return IDENTITY_DEPENDENTS.getOrDefault(kind, Map.of()).getOrDefault(version, List.of());
    }

    /**
     * 선언한 이름이 그 버전에 <b>실제로 있는지</b> 기동할 때 확인한다.
     *
     * ★★ 거르지 않고 <b>터뜨리는</b> 이유 — 이름이 어긋난 것을 조용히 걸러 내면, 거부 재시도가
     *   아무것도 폐기하지 않은 채 "폐기했다" 고 로그를 남기고 <b>어긋난 격자가 그대로 사용자에게 간다.</b>
     *   그림을 열어 봐야만 드러나는 종류라, 배포 전에 기동이 막히는 편이 낫다.
     */
    private void verifyIdentityDependents() {
        IDENTITY_DEPENDENTS.forEach((kind, byVersion) -> byVersion.forEach((version, declared) -> {
            List<String> present = steps(kind, version).stream().map(GenerationStep::name).toList();
            List<String> missing = declared.stream().filter(name -> !present.contains(name)).toList();
            if (!missing.isEmpty()) {
                throw new IllegalStateException(
                        "문단에 기대는 단계로 적힌 이름이 %s %s 에 없습니다: %s (IDENTITY_DEPENDENTS 를 고치세요)"
                                .formatted(kind, version, missing));
            }
        }));
    }

    /** 지금 새로 구울 것에 쓸 버전. */
    public String currentVersion(GenKind kind) {
        return currentVersions.get(kind);
    }
}
