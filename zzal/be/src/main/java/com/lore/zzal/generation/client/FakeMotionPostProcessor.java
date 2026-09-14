package com.lore.zzal.generation.client;

/**
 * 자르는 흉내만 낸다. 실제 파이썬 없이 흐름을 확인할 때 쓴다.
 */
public class FakeMotionPostProcessor implements MotionPostProcessor {

    private final int delayMs;

    public FakeMotionPostProcessor(int delayMs) {
        this.delayMs = delayMs;
    }

    /** ★ 크기도 흉내 낸다 — 진짜와 <b>모양이 같은 값</b>이 나와야 배선이 시험에서 드러난다. */
    @Override
    public Built build(String gridImageKey, String outputPrefix, String profile) throws Exception {
        Thread.sleep(delayMs);
        return new Built(outputPrefix + "/motion.webp", FAKE_WIDTH, FAKE_HEIGHT);
    }

    /** 실측 범위(295~301 x 321~339) 안의 아무 값. 진짜 크기가 아니라는 것을 값으로 말한다. */
    static final int FAKE_WIDTH = 298;
    static final int FAKE_HEIGHT = 330;
}
