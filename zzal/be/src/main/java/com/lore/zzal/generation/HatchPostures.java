package com.lore.zzal.generation;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 격자 단계 → 칸의 자세 유형 매핑을 읽어 온다({@code zzal/pipeline/{버전}/postures.txt}).
 *
 * <h3>★ 왜 있는가 — 후처리가 칸마다 다르게 정렬하기 때문</h3>
 * 서 있는 칸은 <b>발</b>을 기준으로, 앉은 칸·누운 칸은 <b>본체</b>를 기준으로 맞춘다. 어느 칸이 어느
 * 자세인지는 프롬프트가 정하는 것이라, 층이 늘거나 칸 순서가 바뀌면 매핑만 바뀌고 코드는 그대로다
 * (2026-09-12 상훈님 지시 — 칸 번호를 코드에 박지 말 것).
 *
 * <h3>★ 왜 설정(yml)이 아니라 리소스 파일인가</h3>
 * 매핑은 <b>그 버전 프롬프트의 성질</b>이다. 프롬프트를 바꾸면 같이 바뀌고, 안 바꾸면 안 바뀐다.
 * 그래서 프롬프트·스크립트와 같은 자리에 두어 함께 배포되고 함께 버전이 매겨지게 한다.
 * yml 에 두면 배포와 설정이 따로 놀아, 새 프롬프트가 옛 매핑으로 후처리되는 순간이 생긴다.
 *
 * <h3>★ 없으면 빈 문자열 — 있는데 안 적혀 있으면 예외</h3>
 * v1·v2 에는 이 파일이 없다(그 버전의 후처리 스크립트는 {@code --postures} 를 모른다). 그래서 파일이
 * 없으면 조용히 빈 값이다. 그러나 <b>파일은 있는데 그 단계가 안 적혀 있으면</b> 매핑을 빠뜨린 것이므로
 * 무엇이 없는지 말하며 멈춘다 — 조용히 넘어가면 후처리가 1층 기본값으로 되돌아가고,
 * 그건 화면을 봐야만 드러난다.
 */
@Component
public class HatchPostures {

    /** 리소스 자리. {@code PipelineScripts} 가 스크립트를 푸는 그 폴더와 같은 곳이다. */
    static final String PATH = "zzal/pipeline/%s/postures.txt";

    private final Map<String, Map<String, String>> cache = new ConcurrentHashMap<>();

    /**
     * 그 버전·그 격자 단계의 {@code --postures} 문자열. 이 버전에 매핑 파일이 없으면 빈 문자열.
     *
     * @param version 파이프라인 버전(v1·v2·v4…)
     * @param step    격자 단계 이름({@code grid}·{@code grid2})
     */
    public String forStep(String version, String step) {
        Map<String, String> byStep = cache.computeIfAbsent(version, HatchPostures::load);
        if (byStep.isEmpty()) {
            return "";
        }
        String spec = byStep.get(step);
        if (spec == null) {
            // 설정이 원인일 때는 어느 파일의 어느 줄이 없는지 그대로 말한다.
            throw new IllegalStateException(
                    "자세 매핑이 없습니다: %s 에 '%s = ...' 줄을 적으세요 (적힌 단계: %s)"
                            .formatted(PATH.formatted(version), step, byStep.keySet()));
        }
        return spec;
    }

    private static Map<String, String> load(String version) {
        // 파일이 없으면 빈 표 — v1·v2 의 후처리 스크립트는 --postures 를 모른다.
        return ResourceTable.load(PATH.formatted(version));
    }
}
