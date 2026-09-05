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
 * 버전 → 단계 목록.
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

    private final Map<GenKind, Map<String, List<GenerationStep>>> versions;
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
                        "v1", List.of(sheet, identity, grid, post),
                        // v2 = 격자 2장(1층·2층) → 기본 행동 16종(정본 13장). 프롬프트 prompt/v2/{sheet,identity,grid,grid2}.txt
                        "v2", List.of(sheet, identity, grid, grid2, post)),
                GenKind.MOTION, Map.of("v1", List.of(motionGrid, motionPost)));
        this.currentVersions = Map.of(
                GenKind.HATCH, resolveHatchVersion(hatchVersion, resourceExists),
                GenKind.MOTION, motionVersion);
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

    public List<GenerationStep> steps(GenKind kind, String version) {
        List<GenerationStep> steps = versions.getOrDefault(kind, Map.of()).get(version);
        if (steps == null) {
            // 없는 버전으로 굽기 시작하면 조용히 기본값으로 가지 않는다 — 그러면 기록에는
            // v9 라고 남고 실제로는 v1 로 구워진, 설명이 안 되는 결과가 생긴다.
            throw new IllegalArgumentException(
                    "모르는 파이프라인 버전입니다: %s %s".formatted(kind, version));
        }
        return steps;
    }

    /** 지금 새로 구울 것에 쓸 버전. */
    public String currentVersion(GenKind kind) {
        return currentVersions.get(kind);
    }
}
