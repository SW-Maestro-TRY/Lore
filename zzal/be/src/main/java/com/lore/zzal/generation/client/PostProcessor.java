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

    /**
     * v4 — 위와 같되 <b>칸의 자세 유형</b>을 함께 넘긴다({@code --postures}).
     *
     * ★ 왜 따로인가 — 후처리는 서 있는 칸과 앉은·누운 칸을 다르게 정렬한다. 어느 칸이 어느 자세인지는
     *   층마다 다르고(1층은 sick·sleep, 2층은 wash), 그걸 안 넘기면 파이썬이 <b>1층 기본값</b>으로
     *   되돌아가 2층의 reply·wake_up 을 앉기·눕기로 맞춘다. 통과는 하고 그림만 조용히 틀어진다.
     * ★ v1·v2 의 후처리 스크립트는 이 인자를 모른다 — 그래서 빈 문자열이면 안 넘긴다.
     *
     * @param postures {@code "base=standing,...,sick=crouch,...,sleep=lying"}. 이름은 {@code keys} 의 것.
     */
    void split(String gridImageKey, String outputPrefix, String version, List<String> keys, String postures)
            throws Exception;
}
