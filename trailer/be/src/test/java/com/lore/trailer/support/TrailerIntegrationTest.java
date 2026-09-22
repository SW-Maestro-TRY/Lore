package com.lore.trailer.support;

import com.lore.LoreApplication;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Trailer 통합 시험 한 벌 — 진짜 컨텍스트 · 진짜 Postgres · 진짜 시큐리티.
 *
 * <h3>왜 있나</h3>
 * 카드 API 는 <b>DB 가 하는 일</b>이 핵심이다 — {@code Specification} 이 만드는 SQL, {@code LIKE} 이스케이프,
 * {@code id} 순 정렬, Flyway 의 trailer 마이그레이션(V20260922_0847)과 엔티티의 일치({@code ddl-auto: validate}). 단위 시험으로는 한 줄도 못 밟는다.
 * 시큐리티 규칙("GET 만 로그인 없이")도 진짜 필터 체인을 지나야 보인다.
 *
 * <h3>★ zzal 의 {@code @ZzalIntegrationTest} 를 가져다 쓰지 않는다</h3>
 * 도메인끼리는 import 하지 않는다(루트 README). 그래서 같은 모양을 여기에 따로 둔다 — 조건(DB 환경변수 셋),
 * 시험 DB 이름 확인, 메일 자리 메우기가 같다. 다른 점: S3 · 생성 파이프라인을 바꿔 끼우지 않는다.
 * 카드 API 는 그 길을 부르지 않고, 기동만으로는 밖으로 나가는 호출이 없다(NA found.md 5-14).
 *
 * <h3>★ DB 환경변수가 없으면 건너뛴다</h3>
 * {@code LORE_TEST_DB_URL} · {@code LORE_TEST_DB_USERNAME} · {@code LORE_TEST_DB_PASSWORD} 셋이 다 있을 때만 돈다.
 * 다른 기계에서 {@code gradlew test} 를 쳐도 빨갛게 되면 안 된다.
 *
 * <h3>돌리는 법</h3>
 * <pre>
 * export LORE_TEST_DB_URL=jdbc:postgresql://localhost:5432/lore_test
 * export LORE_TEST_DB_USERNAME=... LORE_TEST_DB_PASSWORD=...
 * ./gradlew test --tests 'com.lore.trailer.*'
 * </pre>
 * 스키마는 Flyway 가 V1 부터 전부 적용한다 — trailer 의 V20260922_0847 도 이때 검증된다.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
@SpringBootTest(
        classes = LoreApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                // ── 시험 전용 DB. 개발 DB(lore)와 다른 데이터베이스여야 한다(TrailerItSupport 가 이름으로 막는다) ──
                "spring.datasource.url=${LORE_TEST_DB_URL:}",
                "spring.datasource.username=${LORE_TEST_DB_USERNAME:}",
                "spring.datasource.password=${LORE_TEST_DB_PASSWORD:}",

                // application.yml 의 ${MAIL_USERNAME} 은 기본값이 없어 비어 있으면 기동이 막힌다. SMTP 는 쓰지 않는다.
                "spring.mail.username=trailer-it@example.invalid",
                "spring.mail.password=unused-in-tests",

                // 시험 로그에서 SQL 한 덩어리가 실제 실패를 덮는다.
                "spring.jpa.show-sql=false",

                // ★ 돈이 나가는 길은 닫아 둔다 — 카드 API 와 무관하지만 같은 컨텍스트에 zzal 이 함께 뜬다.
                "app.zzal.generation.real=false",
                "app.zzal.generation.real-postprocess=false",
        })
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "LORE_TEST_DB_URL", matches = ".+",
        disabledReason = "통합 시험용 DB 가 설정되지 않았습니다 — LORE_TEST_DB_URL 을 주면 돕니다")
@EnabledIfEnvironmentVariable(named = "LORE_TEST_DB_USERNAME", matches = ".+",
        disabledReason = "통합 시험용 DB 가 설정되지 않았습니다 — LORE_TEST_DB_USERNAME 을 주면 돕니다")
@EnabledIfEnvironmentVariable(named = "LORE_TEST_DB_PASSWORD", matches = ".+",
        disabledReason = "통합 시험용 DB 가 설정되지 않았습니다 — LORE_TEST_DB_PASSWORD 를 주면 돕니다")
public @interface TrailerIntegrationTest {
}
