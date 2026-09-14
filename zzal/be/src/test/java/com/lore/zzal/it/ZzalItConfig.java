package com.lore.zzal.it;

import com.lore.common.s3.S3Storage;
import com.lore.zzal.generation.MotionPostProfiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.mockito.Mockito.mock;

/**
 * 통합 시험에서 <b>밖으로 나가는 길만</b> 바꿔 끼운다. 나머지는 전부 진짜다.
 *
 * <h3>무엇을 바꾸나 — 셋뿐</h3>
 * <ul>
 *   <li>{@link S3Storage} → {@link InMemoryS3Storage}. 서버가 만든 것을 올리는 자리다</li>
 *   <li>{@link S3Client} · {@link S3Presigner} → 껍데기. 어떤 경로로도 AWS 로 나가지 못하게 덮는다</li>
 *   <li>{@link RealGenerationGuard} — 실제 생성이 켜져 있으면 <b>기동을 막는다</b></li>
 * </ul>
 *
 * <h3>★ 왜 {@code @Primary} 인가 — 이름을 덮어쓰지 않는다</h3>
 * {@code spring.main.allow-bean-definition-overriding} 을 켜면 <b>실수로 덮어쓴 빈도 조용히 통과</b>한다.
 * 그건 이 하네스가 잡으려는 종류의 사고와 같은 모양이다. 그래서 이름을 덮지 않고 우선권만 준다 —
 * 진짜 빈도 그대로 만들어지므로 그쪽 배선이 깨지면 컨텍스트가 뜨지 않는다.
 *
 * <h3>★ 실행기는 바꾸지 않는다</h3>
 * {@code hatchExecutor}(3스레드) · {@code nightExecutor}(2스레드)를 동기 실행기로 갈아 끼우면 편하지만,
 * 그 순간 <b>빈 이름·실행기 배선이 시험에서 사라진다</b> — 그것도 시험 밖에 있던 자리 중 하나다.
 * 대신 {@link ZzalItSupport#await} 로 <b>타임아웃 있는 대기</b>를 한다. 조건이 안 오면 시험은 빨개진다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class ZzalItConfig {

    /** 서버가 만든 그림을 올리고 받는 자리 — 메모리로. */
    @Bean
    @Primary
    public S3Storage inMemoryS3Storage() {
        return new InMemoryS3Storage();
    }

    /**
     * AWS 로 나가는 두 길을 껍데기로 덮는다.
     *
     * ★ 메모리 저장소만 끼우고 여기를 두면, <b>다른 누군가가 S3Client 를 직접 주입받는 순간</b> 진짜로 나간다.
     *   나가는 길은 이름이 아니라 <b>클라이언트</b>로 막는 것이 맞다.
     */
    @Bean
    @Primary
    public S3Client noopS3Client() {
        return mock(S3Client.class);
    }

    @Bean
    @Primary
    public S3Presigner noopS3Presigner() {
        return mock(S3Presigner.class);
    }

    /**
     * 대역 후처리 프로파일 — <b>시험 전용이다. 운영 프로파일과 아무 관계가 없다.</b>
     *
     * <h3>★★ 운영에서는 절대 안 뜬다 — 두 겹으로 막혀 있다</h3>
     * <ol>
     *   <li>이 클래스가 <b>시험 소스 트리</b>({@code src/test})에만 있다. 운영 jar 에 아예 안 들어간다</li>
     *   <li>{@code @TestConfiguration} 은 컴포넌트 스캔에서 <b>제외</b>된다
     *       ({@code TypeExcludeFilter}). 시험이 {@code @Import} 로 <b>직접 불러야만</b> 뜬다</li>
     * </ol>
     * 둘 중 하나만 있어도 막히지만 둘 다 둔다 — 이 대역은 "검수를 거치지 않은 후처리로 굽지 않는다"
     * 는 규칙을 <b>일부러 비켜 가는</b> 물건이라, 운영에 새면 검수 안 된 그림이 사용자에게 나간다.
     * {@code StandInBeansAreTestOnlyTest} 가 그 두 겹을 실제로 확인한다.
     *
     * <h3>★ 왜 필요한가</h3>
     * 후처리 프로파일 표({@code pipeline/{버전}/motion_post_profiles.txt})에는 <b>검수를 마친 동작만</b>
     * 적히고, 표에 없는 동작은 굽지 않는다 — 검수를 거치지 않은 후처리로 구워진 그림은 화면을 봐야만
     * 드러나기 때문이다. 지금 표에 있는 것은 선물 둘(구르기·뒤로넘어짐)뿐이고, <b>3층 심화는 아직
     * 하나도 확정되지 않았다.</b> 그런데 선물은 3층 차례에 안 들어가므로
     * ({@code NightPlanner.nextAdvanced} 가 선물을 뺀다) 조각이 차서 굽는 길은 선물로 시험할 수 없다.
     *
     * <h3>★ 무엇을 대신하나 — 대역 동작 하나뿐</h3>
     * {@code app.zzal.advanced-motions} 에 적힌 대역 동작에만 확정본(구르기)의 프로파일을 빌려준다.
     * 그 밖의 동작은 진짜 표를 그대로 지난다 — "표에 없으면 멈춘다" 는 규칙은 시험에서도 살아 있다
     * (그 규칙 자체는 {@code MotionPipelineV1Test} 가 따로 못 박는다).
     */
    @Bean
    @Primary
    public MotionPostProfiles standInMotionPostProfiles(
            @Value("${app.zzal.advanced-motions:}") String standIns) {
        List<String> keys = Arrays.stream(standIns.split("\\s*,\\s*")).filter(s -> !s.isBlank()).toList();
        return new MotionPostProfiles() {
            @Override
            public String forMotion(String version, String motionKey) {
                return super.forMotion(version, keys.contains(motionKey) ? "roll" : motionKey);
            }
        };
    }

    @Bean
    public RealGenerationGuard realGenerationGuard(
            @Value("${app.zzal.generation.real:false}") boolean real,
            @Value("${app.zzal.generation.real-postprocess:false}") boolean realPostProcess) {
        return new RealGenerationGuard(real, realPostProcess);
    }

    /**
     * 실제 생성이 켜진 채로 시험이 도는 것을 <b>기동에서</b> 막는다.
     *
     * ★ 호출 한 번에 실제로 돈이 나간다. "설정이 켜져 있었다" 를 결과 화면에서 알게 되면 이미 늦다 —
     *   설정이 원인일 때는 설정 이름을 그대로 말하고 뜨지 않는 편이 낫다.
     */
    public static class RealGenerationGuard {

        private static final Logger log = LoggerFactory.getLogger(RealGenerationGuard.class);

        RealGenerationGuard(boolean real, boolean realPostProcess) {
            if (real || realPostProcess) {
                throw new IllegalStateException("""
                        통합 시험에서 실제 생성이 켜져 있습니다 — 돈이 나가는 길이라 기동을 막습니다.
                          app.zzal.generation.real           = %s (ZZAL_GENERATION_REAL)
                          app.zzal.generation.real-postprocess = %s (ZZAL_REAL_POSTPROCESS)
                        둘 다 false 여야 합니다."""
                        .formatted(real, realPostProcess));
            }
            log.info("통합 시험 — 실제 생성 꺼짐 확인(가짜 클라이언트·메모리 저장소)");
        }
    }

    /**
     * 메모리 S3. 올린 것은 맵에 남고, 받는 것은 그 맵에서 꺼내 파일로 쓴다.
     *
     * ★ 빈손으로 성공하지 않는다 — 없는 key 를 받으려 하면 그 자리에서 터진다. 조용히 빈 파일을 주면
     *   뒤 단계가 "왜 결과가 이상한가" 로 한참 뒤에 드러난다.
     */
    public static class InMemoryS3Storage extends S3Storage {

        private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

        InMemoryS3Storage() {
            // ★ 부모는 S3Client 를 들고만 있고 여기서는 두 메서드를 다 덮으므로 쓰이지 않는다.
            super(null, "zzal-integration-test");
        }

        @Override
        public void download(String key, Path to) {
            byte[] bytes = objects.get(key);
            if (bytes == null) {
                throw new IllegalStateException("메모리 S3 에 없는 key 를 받으려 했습니다: " + key);
            }
            try {
                Files.write(to, bytes);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void upload(String key, Path from, String contentType) {
            try {
                objects.put(key, Files.readAllBytes(from));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        /** 시험이 "무엇이 올라갔나" 를 물을 수 있게. */
        public boolean has(String key) {
            return objects.containsKey(key);
        }

        public int size() {
            return objects.size();
        }

        public void clear() {
            objects.clear();
        }
    }
}
