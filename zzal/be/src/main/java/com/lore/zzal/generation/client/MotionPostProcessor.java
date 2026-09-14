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
     * 다 만든 움짤 하나.
     *
     * ★ 키만 돌려주지 않는 이유 — 캔버스 크기가 <b>판마다 다르다</b>(실측 295~301 x 321~339).
     *   그 값을 아는 유일한 자리가 방금 만든 이 파일이고, 여기서 안 들고 나오면 나중에는
     *   S3 에서 다시 받아야만 알 수 있다. 화면이 상수로 가정하면 어떤 판에서만 그림이 어긋나는데
     *   오류가 안 나서 눈으로 봐야만 드러난다.
     *
     * @param imageKey 완성된 움짤의 S3 키
     * @param width    캔버스 가로(px)
     * @param height   캔버스 세로(px)
     */
    record Built(String imageKey, int width, int height) {
    }

    /**
     * @param gridImageKey 16프레임 격자의 S3 키
     * @param outputPrefix 결과를 올릴 폴더
     * @param profile      이 동작의 후처리 프로파일({@code script=state16_v3, align=seat, ...}).
     *                     표는 {@code pipeline/{버전}/motion_post_profiles.txt} 에 있다.
     *                     ★비어 있으면 스크립트가 <b>멈춘다</b> — 기본 후처리로 떨어지지 않는다.
     *                     동작마다 무엇을 기준으로 칸을 맞추는지가 다르고(구르기=발·넘어짐=접지앵커),
     *                     엉뚱한 기준으로 구워진 그림은 화면을 봐야만 드러난다
     * @return 완성된 움짤의 키와 캔버스 크기
     */
    Built build(String gridImageKey, String outputPrefix, String profile) throws Exception;
}
