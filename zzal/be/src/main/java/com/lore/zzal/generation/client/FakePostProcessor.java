package com.lore.zzal.generation.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/** 실제로 자르지 않는 후처리. 파이썬 연동 전까지 흐름 확인용. */
public class FakePostProcessor implements PostProcessor {

    private static final Logger log = LoggerFactory.getLogger(FakePostProcessor.class);

    private final int delayMillis;

    public FakePostProcessor(int delayMillis) {
        this.delayMillis = delayMillis;
    }

    @Override
    public Session open(String outputPrefix, String version) {
        log.info("[가짜] 후처리 세션 열기 {} — {}", version, outputPrefix);
        return new FakeSession(outputPrefix, version);
    }

    /**
     * ★ 진짜와 <b>같은 수명</b>을 흉내 낸다 — 세션을 열고 층마다 자르고 닫는다.
     *   흐름 확인용이라 파일을 만들지도, 올리지도 않는다.
     */
    private final class FakeSession implements Session {

        private final String outputPrefix;
        private final String version;

        FakeSession(String outputPrefix, String version) {
            this.outputPrefix = outputPrefix;
            this.version = version;
        }

        @Override
        public void split(String gridImageKey) throws InterruptedException {
            log.info("[가짜] 후처리 {} — grid={} → {}/*.webp", version, gridImageKey, outputPrefix);
            Thread.sleep(delayMillis);
        }

        @Override
        public void split(String gridImageKey, List<String> keys) throws InterruptedException {
            split(gridImageKey, keys, "");
        }

        @Override
        public void split(String gridImageKey, List<String> keys, String postures) throws InterruptedException {
            log.info("[가짜] 후처리 {} — grid={} → {}/{{{}}}.webp (자세 {})",
                    version, gridImageKey, outputPrefix, String.join(",", keys),
                    postures == null || postures.isBlank() ? "기본" : postures);
            Thread.sleep(delayMillis);
        }

        @Override
        public void close() {
            log.info("[가짜] 후처리 세션 닫기 — {}", outputPrefix);
        }
    }
}
