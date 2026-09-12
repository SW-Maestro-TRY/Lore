package com.lore.zzal.it;

import com.lore.LoreApplication;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 통합 시험 한 벌 — <b>진짜 컨텍스트 · 진짜 DB · 진짜 이벤트</b>, 가짜는 돈이 나가는 길뿐.
 *
 * <h3>왜 있나 — 단위 시험이 원리적으로 못 보는 자리가 있다</h3>
 * zzal 의 단위 시험은 한 덩어리 안(엔티티·계산 규칙)은 촘촘하지만 덩어리를 잇는 배선을 한 줄도 안 밟는다.
 * {@code @TransactionalEventListener(AFTER_COMMIT)} · {@code @Async} · {@code REQUIRES_NEW} · 수기 JPQL 은
 * <b>스프링 컨텍스트와 진짜 트랜잭션이 있어야만</b> 돈다. 목(mock)으로는 "부품은 맞는데 이어 붙이면 안 돈다" 를
 * 영원히 못 본다.
 *
 * <h3>★★ 시험을 {@code @Transactional} 로 감싸 롤백하지 않는다</h3>
 * 그러면 커밋이 없어 {@code AFTER_COMMIT} 리스너가 <b>영영 안 돈다</b> — 정확히 이 하네스가 보려던 자리가
 * 통째로 사라진다. 대신 시험마다 {@link ZzalItSupport} 가 표를 비운다.
 *
 * <h3>★★ 돈이 나가는 길은 열리지 않는다</h3>
 * {@code app.zzal.generation.real=false} 로 고정하고, {@link ZzalItConfig.RealGenerationGuard} 가
 * 혹시라도 켜져 있으면 <b>기동을 막는다</b>. S3 는 메모리 저장소로 바꾸고 AWS 클라이언트는 껍데기로 덮는다.
 *
 * <h3>★ DB 환경변수가 없으면 실패가 아니라 건너뛴다</h3>
 * 다른 기계에서 {@code gradlew test} 를 쳐도 빨갛게 되면 안 된다. 세 변수가 다 있을 때만 돈다 —
 * {@code LORE_TEST_DB_URL} · {@code LORE_TEST_DB_USERNAME} · {@code LORE_TEST_DB_PASSWORD}.
 * (JUnit 의 조건이라 컨텍스트를 만들기 <b>전에</b> 판정된다 — 없는 DB 로 붙어 보다 터지지 않는다.)
 *
 * <h3>붙이는 법</h3>
 * <pre>
 * &#64;ZzalIntegrationTest
 * class 무엇무엇IT extends ZzalItSupport { ... }
 * </pre>
 *
 * <h3>돌리는 법</h3>
 * <pre>
 * export LORE_TEST_DB_URL=jdbc:postgresql://localhost:5432/lore_test
 * export LORE_TEST_DB_USERNAME=... LORE_TEST_DB_PASSWORD=...
 * ./gradlew test
 * </pre>
 * 스키마는 Flyway 가 V1 부터 전부 적용한다({@code ddl-auto: validate} 그대로) — 마이그레이션 자체가 매번 검증된다.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
@SpringBootTest(
        classes = {LoreApplication.class, ZzalItConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                // ── 시험 전용 DB. 운영·개발 DB(lore)와 다른 데이터베이스여야 한다(ZzalItSupport 가 이름으로 막는다) ──
                "spring.datasource.url=${LORE_TEST_DB_URL:}",
                "spring.datasource.username=${LORE_TEST_DB_USERNAME:}",
                "spring.datasource.password=${LORE_TEST_DB_PASSWORD:}",

                // ★ 비밀번호를 여기 적지 않는다 — 환경변수로만 온다. 아래 둘은 SMTP 를 안 쓰는 자리를 메우는 값이다
                //   (application.yml 의 ${MAIL_USERNAME} 은 기본값이 없어 비어 있으면 기동이 막힌다).
                "spring.mail.username=zzal-it@example.invalid",
                "spring.mail.password=unused-in-tests",

                // 시험 로그에서 SQL 한 덩어리가 실제 실패를 덮는다.
                "spring.jpa.show-sql=false",

                // ★★ 돈이 나가는 길 — 가짜로 못 박는다. 켜져 있으면 RealGenerationGuard 가 기동을 막는다.
                "app.zzal.generation.real=false",
                "app.zzal.generation.real-postprocess=false",
                // 가짜 클라이언트의 흉내 지연. 0 이면 시험이 기다릴 것이 없다.
                "app.zzal.generation.fake-delay-ms=0",

                // ★ 시험이 시간을 밀 수 있어야 한다 — 개발 시계 API(/api/zzal/v1/dev/**)를 켠다.
                //   새 시계를 만들지 않고 운영에 있는 그 장치를 그대로 쓴다.
                "app.zzal.dev-tools=true",

                // ★ 밤에 구울 수 있는 심화 행동 — 지시문 파일이 있는 것 하나(zzal/prompt/v1/motions/교감자세.txt).
                //   비어 있으면 조각이 다 차도 "구울 심화가 없다" 로 끝나 굽기 경로가 한 줄도 안 돈다.
                "app.zzal.advanced-motions=shy",

                // 23:00 스위프는 끈다 — 시험이 보는 것은 "조건을 채운 그 순간" 의 굽기다.
                "app.zzal.night.sweep-enabled=false",

                // S3 는 메모리 저장소로 덮지만(ZzalItConfig), 버킷 이름이 비면 경고만 남고 의미가 흐려진다.
                "app.s3.content-bucket=zzal-integration-test",
        })
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "LORE_TEST_DB_URL", matches = ".+",
        disabledReason = "통합 시험용 DB 가 설정되지 않았습니다 — LORE_TEST_DB_URL 을 주면 돕니다")
@EnabledIfEnvironmentVariable(named = "LORE_TEST_DB_USERNAME", matches = ".+",
        disabledReason = "통합 시험용 DB 가 설정되지 않았습니다 — LORE_TEST_DB_USERNAME 을 주면 돕니다")
@EnabledIfEnvironmentVariable(named = "LORE_TEST_DB_PASSWORD", matches = ".+",
        disabledReason = "통합 시험용 DB 가 설정되지 않았습니다 — LORE_TEST_DB_PASSWORD 를 주면 돕니다")
public @interface ZzalIntegrationTest {
}
