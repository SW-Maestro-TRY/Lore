package com.lore.trailer.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lore.common.user.User;
import com.lore.common.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Trailer 통합 시험이 공통으로 쓰는 것 — 시험 DB 확인 · 표본 25장 넣기 · HTTP 읽기.
 *
 * <h3>★ 표본은 시험마다 다시 넣는다</h3>
 * {@code trailer/be/src/test/resources/foreshadowings_sample.sql} 은 NA 의 {@code export_cards_sql.py} 가
 * 화면 검사 자료(trailer/fe/tests/fixtures/cards-ch400-sample.json)와 <b>같은 25장</b>으로 만든 파일이다.
 * 파일 안에 {@code TRUNCATE … RESTART IDENTITY} 가 있어 넣기가 곧 초기화다. 끝나면 표를 비운다 —
 * 남은 줄이 다음 시험을 엉뚱하게 통과시키지 않게.
 *
 * <h3>★ 파일은 클래스패스가 아니라 레포 경로로 읽는다</h3>
 * {@code build.gradle} 의 시험 소스셋에는 도메인의 {@code resources} 폴더가 없다. 팀 공용 파일을 고치는 대신
 * zzal 의 {@code SchemaNameCollisionTest} 처럼 {@code settings.gradle} 이 있는 곳을 루트로 잡아 읽는다.
 *
 * <h3>★★ 비울 DB 를 이름으로 확인한다</h3>
 * 개발 DB(lore)를 가리킨 채 돌면 카드 1,651장이 25장으로 갈린다. 데이터베이스 이름이 {@code _test} 로
 * 끝나지 않으면 컨텍스트를 만들기 전에 멈춘다(zzal 의 ZzalItSupport 와 같은 규칙).
 */
public abstract class TrailerItSupport {

    /** 표본 SQL 의 레포 안 자리. */
    protected static final String SAMPLE_SQL = "trailer/be/src/test/resources/foreshadowings_sample.sql";

    /** 화면 검사 자료 — 표본 SQL 과 같은 25장. 카드 칸 열둘을 견줄 때 쓴다. */
    protected static final String SAMPLE_FIXTURE = "trailer/fe/tests/fixtures/cards-ch400-sample.json";

    @Autowired protected MockMvc mockMvc;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected UserRepository userRepository;
    /** 시험용 사용자 이메일의 번호. 같은 JVM 안에서 겹치지 않게 */
    private static final AtomicLong SEQ = new AtomicLong();
    protected final ObjectMapper json = new ObjectMapper();

    // ── 안전장치 ──────────────────────────────────────────────────────────

    @BeforeAll
    static void refuseAnythingButATestDatabase() {
        String url = System.getenv("LORE_TEST_DB_URL");
        if (url == null || url.isBlank()) {
            return;     // 조건 애노테이션이 이미 건너뛰게 했다
        }
        String database = url.replaceAll("[?].*$", "").replaceAll("^.*/", "");
        assertThat(database)
                .as("통합 시험은 시험 전용 DB 에서만 돕니다 — 표를 비우기 때문입니다. "
                        + "LORE_TEST_DB_URL 의 데이터베이스 이름이 _test 로 끝나야 합니다(지금: %s)", database)
                .endsWith("_test");
    }

    @BeforeEach
    void assertPointedAtTestDatabase() {
        String database = jdbc.queryForObject("select current_database()", String.class);
        assertThat(database).as("표를 비울 DB").endsWith("_test");
    }

    // ── 표본 ──────────────────────────────────────────────────────────────

    /** 표본 25장을 넣는다. 파일이 먼저 표를 비우므로 몇 번을 불러도 같은 25장이다. */
    @BeforeEach
    void loadSample() throws IOException {
        jdbc.execute(Files.readString(repoRoot().resolve(SAMPLE_SQL), StandardCharsets.UTF_8));
        assertThat(count()).as("표본 25장").isEqualTo(25);
    }

    /** 카드 표를 비우고, 가설 표 둘과 시험이 만든 사용자도 지운다 — 다음 시험이 빈 표에서 시작하게. */
    @AfterEach
    void emptyTheTables() {
        truncate();
        truncateHypotheses();
        jdbc.update("delete from users where email like 'trailer-it-%'");
    }

    protected void truncate() {
        jdbc.execute("TRUNCATE TABLE foreshadowings RESTART IDENTITY");
    }

    protected void truncateHypotheses() {
        jdbc.execute("TRUNCATE TABLE hypothesis_foreshadowing, hypotheses RESTART IDENTITY CASCADE");
    }

    /* ---- 로그인한 독자 ------------------------------------------------------------ */

    /** 시험용 사용자를 만든다. zzal 시험과 같은 방식(User.signUp)이다. 끝나면 지운다. */
    protected Long newUserId() {
        return userRepository.save(User.signUp("trailer-it-%d-%d@example.invalid"
                .formatted(System.nanoTime(), SEQ.incrementAndGet()))).getId();
    }

    /** 운영자로 만든다. lore 의 운영자는 users.role 이 ADMIN 인 사용자다(found.md 5-13). */
    protected void makeAdmin(Long userId) {
        jdbc.update("update users set role = 'ADMIN' where id = ?", userId);
    }

    /** 그 사용자로 로그인한 요청. JwtAuthenticationFilter 가 넣는 것과 같은 모양(주체는 userId, 권한은 ROLE_USER). */
    protected RequestPostProcessor asUser(Long userId) {
        return authentication(new UsernamePasswordAuthenticationToken(
                userId, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    protected MvcResult getAs(Long userId, String path) throws Exception {
        return mockMvc.perform(get(path).with(asUser(userId))).andReturn();
    }

    /** JSON 몸통을 보낸다. userId 가 null 이면 로그인 없이 보낸다. body 가 String 이면 그대로(틀린 JSON 을 보낼 때). */
    protected MvcResult postJson(Long userId, String path, Object body) throws Exception {
        MockHttpServletRequestBuilder request = post(path).contentType(MediaType.APPLICATION_JSON)
                .content(body instanceof String raw ? raw : json.writeValueAsString(body));
        if (userId != null) {
            request.with(asUser(userId));
        }
        return mockMvc.perform(request).andReturn();
    }

    protected String errorMessage(MvcResult result) throws Exception {
        return body(result).path("error").path("message").asText("");
    }

    protected long count() {
        Long n = jdbc.queryForObject("select count(*) from foreshadowings", Long.class);
        return n == null ? 0 : n;
    }

    /** 화면 검사 자료의 카드 25장(400화 기준 값). */
    protected JsonNode sampleFixtureCards() throws IOException {
        return json.readTree(Files.readString(repoRoot().resolve(SAMPLE_FIXTURE), StandardCharsets.UTF_8)).path("cards");
    }

    // ── HTTP (로그인 없이) ────────────────────────────────────────────────

    /**
     * 로그인 없이 GET. 카드 API 는 모두 이렇게 불린다.
     *
     * <p>★ 쿼리 값은 <b>인코딩하지 않은 날것</b>으로 적는다("search=luffy shanks", "kind=약속", "search=%").
     * {@code MockMvcRequestBuilders.get(uri)} 는 주소를 템플릿으로 보고 스스로 인코딩해서, 미리 인코딩해 주면
     * 두 번 인코딩된다 — {@code %EC%95%BD} 가 {@code %25EC%2595...} 로 가고 {@code +} 는 빈칸으로 안 풀린다.
     * 그래서 여기서 쿼리를 잘라 {@code queryParam} 으로 하나씩 넘긴다. 값에 {@code &} 는 없다고 본다.
     */
    protected MvcResult getAnonymously(String pathAndQuery) throws Exception {
        int mark = pathAndQuery.indexOf('?');
        MockHttpServletRequestBuilder request = get(mark < 0 ? pathAndQuery : pathAndQuery.substring(0, mark));
        if (mark >= 0) {
            for (String pair : pathAndQuery.substring(mark + 1).split("&")) {
                if (pair.isEmpty()) {
                    continue;
                }
                int eq = pair.indexOf('=');
                request.queryParam(eq < 0 ? pair : pair.substring(0, eq), eq < 0 ? "" : pair.substring(eq + 1));
            }
        }
        return mockMvc.perform(request).andReturn();
    }

    protected int status(MvcResult result) {
        return result.getResponse().getStatus();
    }

    protected JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** 성공 응답의 {@code data}. 200 이 아니면 응답 전체를 보이며 실패한다. */
    protected JsonNode data(MvcResult result) throws Exception {
        assertThat(status(result))
                .as("응답=%s", result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo(200);
        JsonNode body = body(result);
        assertThat(body.path("success").asBoolean()).isTrue();
        return body.path("data");
    }

    /** 실패 응답의 {@code error.code}. 없으면 null. */
    protected String errorCode(MvcResult result) throws Exception {
        JsonNode error = body(result).path("error");
        return error.isMissingNode() || error.isNull() ? null : error.path("code").asText(null);
    }

    // ── 자리 찾기 ─────────────────────────────────────────────────────────

    /** 시험은 레포 어디서 돌든 루트를 찾아야 한다. */
    protected static Path repoRoot() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.exists(p.resolve("settings.gradle"))) {
            p = p.getParent();
        }
        if (p == null) {
            throw new IllegalStateException("레포 루트를 못 찾았습니다");
        }
        return p;
    }
}
