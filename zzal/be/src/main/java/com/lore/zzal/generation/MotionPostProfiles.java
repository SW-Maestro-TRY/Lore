package com.lore.zzal.generation;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 동작 key → 16프레임 후처리 프로파일({@code zzal/pipeline/{버전}/motion_post_profiles.txt}).
 *
 * <h3>★ 왜 동작마다 다른가</h3>
 * 16프레임은 한 칸이 독립이 아니라 <b>한 동작이 이어지는 하나의 루프</b>다. 그래서 "무엇을 기준으로
 * 칸을 맞출 것인가" 가 동작의 성질을 탄다.
 * <pre>
 *   구르기      발이 공중에 뜨는 칸이 있지만 판정은 발 정렬(state16_v2)로 받았다
 *   뒤로넘어짐  뒤로 눕는 칸에서 발 기준이 통째로 오판된다. 엉덩이·등이 닿는 자리
 *               (접지앵커)를 기준으로 맞춘다(state16_v3 · align=seat)
 * </pre>
 * 칸 번호를 코드에 박지 말라는 2026-09-12 지시의 16프레임 판이다 — 코드가 아니라 표를 고친다.
 *
 * <h3>★★ 없으면 굽지 않는다 — 기본값으로 떨어지지 않는다</h3>
 * 기본 프로파일을 두면, 새 동작을 확정하고 이 표에 줄을 <b>깜빡했을 때</b> 판정받지 않은 후처리로
 * 구워진 그림이 그대로 사용자에게 간다. 굽기는 성공하고 로그도 깨끗하다 — 화면을 봐야만 드러난다.
 * 그래서 표에 없으면 <b>파일 이름과 빠진 key 를 말하며 멈춘다.</b>
 */
@Component
public class MotionPostProfiles {

    static final String PATH = "zzal/pipeline/%s/motion_post_profiles.txt";

    private final Map<String, Map<String, String>> cache = new ConcurrentHashMap<>();

    /**
     * 그 동작의 후처리 프로파일 문자열({@code script=state16_v3, align=seat, ...}).
     *
     * @param version   모션 파이프라인 버전({@code app.zzal.motion-pipeline-version})
     * @param motionKey 카탈로그 key({@code roll}·{@code fall_back}…)
     * @throws IllegalStateException 표에 그 동작이 없을 때 — 어느 파일에 무엇을 적어야 하는지 말한다
     */
    public String forMotion(String version, String motionKey) {
        Map<String, String> table = cache.computeIfAbsent(version, v -> ResourceTable.load(PATH.formatted(v)));
        String profile = table.get(motionKey);
        if (profile == null || profile.isBlank()) {
            throw new IllegalStateException(
                    ("'%s' 의 후처리 프로파일이 없습니다. %s 에 `%s = script=..., ...` 줄을 적으세요 "
                            + "(적힌 동작: %s). 기본값으로 굽지 않습니다 — 판정받지 않은 후처리로 구워진 "
                            + "그림은 화면을 봐야만 드러납니다")
                            .formatted(motionKey, PATH.formatted(version), motionKey,
                                    table.isEmpty() ? "없음" : table.keySet()));
        }
        return profile;
    }
}
