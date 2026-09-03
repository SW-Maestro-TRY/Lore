package com.lore.zzal.generation.client;

/**
 * 16프레임 격자 한 장을 움짤 하나로 만든다.
 *
 * ★ 부화용 {@link PostProcessor} 와 갈라 둔 이유 — 하는 일이 다르다.
 *   부화는 한 장에서 <b>8개</b>가 나오고 각 칸이 독립인 2프레임 쌍이라 "쌍 안 정렬 → 쌍 사이 정렬"
 *   2층으로 맞춘다. 모션은 한 장이 <b>하나의 이어지는 동작</b>이라 16칸을 통째로 발 좌표
 *   중앙값에 맞춘다. 쌍 정렬을 그대로 쓰면 스쿼트처럼 키가 일부러 변하는 동작에서
 *   앉은 칸이 통째로 밀린다(실험에서 확인돼 스크립트가 이미 갈라져 있다).
 */
public interface MotionPostProcessor {

    /**
     * @param gridImageKey 16프레임 격자의 S3 키
     * @param outputPrefix 결과를 올릴 폴더
     * @return 완성된 움짤의 S3 키
     */
    String build(String gridImageKey, String outputPrefix) throws Exception;
}
