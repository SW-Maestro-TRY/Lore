package com.lore.zzal.generation.client;

/**
 * 자르는 흉내만 낸다. 실제 파이썬 없이 흐름을 확인할 때 쓴다.
 */
public class FakeMotionPostProcessor implements MotionPostProcessor {

    private final int delayMs;

    public FakeMotionPostProcessor(int delayMs) {
        this.delayMs = delayMs;
    }

    @Override
    public String build(String gridImageKey, String outputPrefix) throws Exception {
        Thread.sleep(delayMs);
        return outputPrefix + "/motion.webp";
    }
}
