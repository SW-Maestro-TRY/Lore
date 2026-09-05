package com.lore.zzal.generation.client;

/**
 * 격자를 잘라 8개 움짤을 만든다.
 *
 * ★ 구현이 둘이다.
 *     FakePostProcessor    지금. 실제로 자르지 않는다
 *     PythonPostProcessor  다음. 검증된 파이썬 스크립트를 실행한다
 *
 * ★ 파이썬을 쓰는 이유 — 초록 키잉·발 정렬 로직이 실험에서 여러 사고를 잡아 가며 다듬은
 *   것이라(369줄), 자바로 다시 쓰면 그 사고들이 되살아날 위험이 크다.
 *   서버에는 파이썬과 numpy·scipy·Pillow 가 필요하고, 새 EC2 가 뜰 때 자동 설치된다.
 */
public interface PostProcessor {

    /**
     * @param gridImageKey 4x4 격자의 S3 키
     * @param outputPrefix 결과를 올릴 폴더. 여기에 idle.webp · eat.webp … 8개가 생긴다
     */
    void split(String gridImageKey, String outputPrefix) throws Exception;
}
