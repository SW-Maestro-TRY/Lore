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
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 버전 → 단계 목록. <b>단계는 묶음(stage) 단위로 돈다.</b>
 *
 * <h3>★ 묶음이 곧 "나란히 돌려도 되는가"의 선언이다</h3>
 * 한 묶음 안의 단계들은 <b>동시에</b> 돌고, 묶음과 묶음 사이는 순서대로다.
 * v2 의 {@code [grid, grid2]} 가 그 예다 — 두 장은 서로를 안 보고 둘 다 {@code identity} 하나만 쓰므로
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
 * 예) 정체성 문단을 없앤 v2 는 이렇게 된다
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
                    "v1", List.of(IdentityStep.NAME, GridStep.NAME),
                    "v2", List.of(IdentityStep.NAME, GridStep.NAME, PostProcessStep.GRID2),
                    // v4 도 문단을 재료로 쓴다(단계 구성이 v2 와 같다). 2층(grid2)은 아직 목록에 없다.
                    "v4", List.of(IdentityStep.NAME, GridStep.NAME)),
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
        this(sheet, identity, grid, grid2, post, motionGrid, motionPost, hatchVersion, motionVersion,
                path -> new ClassPathResource(path).exists());
    }

    /** 테스트용 — 프롬프트 파일이 있는지를 밖에서 정한다. */
    PipelineRegistry(SheetStep sheet, IdentityStep identity, GridStep grid, GridStep grid2, PostProcessStep post,
                     MotionGridStep motionGrid, MotionPostStep motionPost,
                     String hatchVersion, String motionVersion, java.util.function.Predicate<String> resourceExists) {
        this.versions = Map.of(
                GenKind.HATCH, Map.of(
                        "v1", List.of(List.of(sheet), List.of(identity), List.of(grid), List.of(post)),
                        // v2 = 격자 2장(1층·2층) → 기본 행동 16종(정본 13장). 프롬프트 prompt/v2/{sheet,identity,grid,grid2}.txt
                        // ★ [grid, grid2] 가 한 묶음 = 나란히 굽는다. identity 는 앞 묶음이라 반드시 먼저 끝난다.
                        "v2", List.of(List.of(sheet), List.of(identity), List.of(grid, grid2), List.of(post)),
                        // v4 = 1층 격자 1장 → 기본 행동 8종. 프롬프트 prompt/v4/{sheet,identity,grid}.txt,
                        // 후처리 pipeline/v4/service_post.py(= 2026-09-12 판정 확정 조합 state8_v5 + 격자 게이트).
                        // ★ 2층(grid2)은 아직 없다 — 확정되면 prompt/v4/grid2.txt 를 넣고 이 목록에 grid2 를 끼운다.
                        //   그 순간 PostProcessStep 이 v2 와 같은 길(카탈로그 key 16종)로 자동으로 넘어간다.
                        "v4", List.of(List.of(sheet), List.of(identity), List.of(grid), List.of(post))),
                GenKind.MOTION, Map.of("v1", List.of(List.of(motionGrid), List.of(motionPost))));
        this.currentVersions = Map.of(
                GenKind.HATCH, resolveHatchVersion(hatchVersion, resourceExists),
                GenKind.MOTION, motionVersion);
        verifyIdentityDependents();
    }

    /**
     * v2 를 켰는데 프롬프트가 아직 없으면(생성 세션 PR 미머지) v1 로 기동한다 — 부팅 로그에 크게 남긴다.
     *
     * ★ 조용히 v1 로 가지 않는다. 설정은 v2 인데 기록은 v1 로 남는 것이 "설명이 안 되는 결과" 이므로,
     *   기록({@code hatchPipelineVersion})도 여기서 정한 v1 로 남고 로그가 그 이유를 말한다.
     */
    private static String resolveHatchVersion(String configured, java.util.function.Predicate<String> resourceExists) {
        if (!"v2".equals(configured)) {
            return configured;
        }
        List<String> missing = List.of("sheet", "identity", "grid", "grid2").stream()
                .map(n -> "zzal/prompt/v2/" + n + ".txt")
                .filter(path -> !resourceExists.test(path))
                .toList();
        if (missing.isEmpty()) {
            return "v2";
        }
        log.warn("★ app.zzal.pipeline-version=v2 인데 프롬프트가 없어 부화를 v1 로 기동합니다 — 없는 파일: {}", missing);
        return "v1";
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
