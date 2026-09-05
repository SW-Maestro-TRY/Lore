package com.lore.zzal.generation.client;

import java.util.List;

/**
 * 격자를 잘라 기본 행동 webp 를 만든다.
 *
 * ★ 구현이 둘 — {@code FakePostProcessor}(지금, 실제로 안 자름) · {@code PythonPostProcessor}(검증된 파이썬 실행).
 * ★ <b>버전을 호출마다 받는다</b> — 빈이 만들어질 때의 설정 버전이 아니라 <b>그 job 의 버전</b>(폴백으로 v1 이 됐을 수
 *   있다, #218 리뷰)으로 스크립트와 출력 이름을 고른다.
 */
public interface PostProcessor {

    /**
     * v1 — 격자 1장 → 8상태. 출력 이름은 설정 {@code app.zzal.hatch.states.{version}}.
     *
     * @param gridImageKey 4x4 격자의 S3 키
     * @param outputPrefix 결과를 올릴 폴더. {@code {prefix}/{state}.webp}
     */
    void split(String gridImageKey, String outputPrefix, String version) throws Exception;

    /**
     * v2 — 격자 한 장을 <b>카탈로그 key 이름</b>으로 자른다(8개). 파이썬에 {@code --keys} 로 넘긴다.
     * 출력 = {@code {outputPrefix}/{key}.webp}. 두 장(grid·grid2)이면 두 번 부른다.
     */
    void split(String gridImageKey, String outputPrefix, String version, List<String> keys) throws Exception;
}
