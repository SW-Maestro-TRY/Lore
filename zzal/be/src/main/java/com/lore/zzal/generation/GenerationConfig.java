package com.lore.zzal.generation;

import com.lore.common.s3.S3Storage;
import com.lore.zzal.generation.client.FakeImageClient;
import com.lore.zzal.generation.client.FakePostProcessor;
import com.lore.zzal.generation.client.FakeTextClient;
import com.lore.zzal.generation.client.ImageClient;
import com.lore.zzal.generation.client.PostProcessor;
import com.lore.zzal.generation.client.PythonPostProcessor;
import com.lore.zzal.generation.client.TextClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 진짜로 부를지, 흉내만 낼지 고른다.
 *
 * ★ 기본이 가짜인 이유 — 호출 한 번에 실제로 돈이 나간다($0.19). 개발 중 왕복마다
 *   그 돈이 나가면 안 되고, 실수로 켜져 있는 것보다 실수로 꺼져 있는 편이 훨씬 낫다.
 *   실제 호출은 **명시적으로 켤 때만** 돈다.
 *
 *   app.zzal.generation.real=false   가짜. 과금 0 (기본)
 *   app.zzal.generation.real=true    실제 OpenAI 호출
 *
 * ★ 후처리는 따로 켠다(real-postprocess) — **돈이 안 들기 때문**이다.
 *   우리 서버 안 계산이라, 실제 파이썬을 돌리면서도 API 호출은 가짜로 둘 수 있다.
 *   그래야 "자르기가 제대로 되는가" 를 과금 없이 확인할 수 있다.
 */
@Configuration
public class GenerationConfig {

    @Bean
    public ImageClient imageClient(@Value("${app.zzal.generation.real:false}") boolean real,
                                   @Value("${app.zzal.generation.fake-delay-ms:4000}") int delay,
                                   @Value("${app.zzal.generation.fake-grid-key:}") String fakeGridKey) {
        if (real) {
            throw new IllegalStateException(
                    "실제 이미지 클라이언트는 아직 없습니다. #132 4번 걸음에서 붙입니다.");
        }
        return new FakeImageClient(delay, fakeGridKey);
    }

    @Bean
    public TextClient textClient(@Value("${app.zzal.generation.real:false}") boolean real,
                                 @Value("${app.zzal.generation.fake-delay-ms:4000}") int delay) {
        if (real) {
            throw new IllegalStateException(
                    "실제 텍스트 클라이언트는 아직 없습니다. #132 4번 걸음에서 붙입니다.");
        }
        return new FakeTextClient(delay / 3);
    }

    @Bean
    public PostProcessor postProcessor(
            @Value("${app.zzal.generation.real-postprocess:false}") boolean real,
            S3Storage storage, PipelineScripts scripts,
            @Value("${app.zzal.python.bin:python3}") String pythonBin,
            @Value("${app.zzal.pipeline-version:v1}") String version,
            @Value("${app.zzal.python.timeout-seconds:60}") int timeout) {
        if (real) {
            return new PythonPostProcessor(storage, scripts, pythonBin, version, timeout);
        }
        return new FakePostProcessor(500);
    }
}
