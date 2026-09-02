package com.lore.zzal.generation;

import com.lore.zzal.generation.client.FakeImageClient;
import com.lore.zzal.generation.client.FakePostProcessor;
import com.lore.zzal.generation.client.FakeTextClient;
import com.lore.zzal.generation.client.ImageClient;
import com.lore.zzal.generation.client.PostProcessor;
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
 */
@Configuration
public class GenerationConfig {

    @Bean
    public ImageClient imageClient(@Value("${app.zzal.generation.real:false}") boolean real,
                                   @Value("${app.zzal.generation.fake-delay-ms:4000}") int delay) {
        if (real) {
            throw new IllegalStateException(
                    "실제 이미지 클라이언트는 아직 없습니다. #132 4번 걸음에서 붙입니다.");
        }
        return new FakeImageClient(delay);
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
    public PostProcessor postProcessor(@Value("${app.zzal.generation.real:false}") boolean real) {
        if (real) {
            throw new IllegalStateException(
                    "파이썬 후처리는 아직 없습니다. #132 3번 걸음에서 붙입니다.");
        }
        return new FakePostProcessor(500);
    }
}
