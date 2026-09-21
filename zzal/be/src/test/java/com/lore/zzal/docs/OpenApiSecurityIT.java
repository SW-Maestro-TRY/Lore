package com.lore.zzal.docs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lore.zzal.it.ZzalIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * API 문서가 <b>인증에 대해</b> 사실을 말하는지 붙잡아 둔다.
 *
 * <h3>★ 무엇을 막나 — 문서만 틀리고 서버는 멀쩡한 상태</h3>
 * 아래 둘은 고쳐 놓아도 <b>되돌아가면 아무 소리도 안 난다.</b> 서버는 그대로 돌고,
 * 빌드도 배포도 통과하고, 틀린 것은 사람이 읽는 문서뿐이라 실제로 따라 해 본 사람만 헤맨다.
 * <ol>
 *   <li><b>{@code userId} 가 필수 쿼리 파라미터로 보이던 것</b> — 쿠키에서 꺼내는 값인데
 *       문서에는 "붙여서 불러야 하는 값" 으로 그려졌다. 붙여도 조용히 무시된다</li>
 *   <li><b>자물쇠가 하나도 없던 것</b> — 인증 방식이 문서에 없어 전부 "로그인 없이 되는 API"
 *       처럼 보였다</li>
 * </ol>
 *
 * <h3>★★ 여는 쪽이 더 위험하다 — 그래서 양쪽을 다 본다</h3>
 * 자물쇠를 빠뜨리는 것보다 <b>열린 주소에 자물쇠를 그리는 쪽</b>이 더 나쁘다. 랜딩·회원가입처럼
 * 로그인 없이 되어야 할 것이 "로그인해야 한다" 로 읽히면 프론트가 굳이 막아 버린다.
 * 그래서 이 시험은 "붙었나" 만이 아니라 <b>"안 붙어야 할 곳에 안 붙었나"</b> 를 같이 본다.
 *
 * <h3>★ 기대값은 이 파일 안에 따로 적는다</h3>
 * {@link #shouldRequireLogin}은 실제 권한 규칙({@code WebSecurityConfig} 의
 * {@code authorizeHttpRequests})을 <b>보고 옮겨 적은 것</b>이고, 문서를 만드는 쪽 코드를 쓰지 않는다.
 * 같은 함수를 갖다 쓰면 그 함수가 틀렸을 때 시험도 똑같이 틀려서 영원히 초록이 된다.
 *
 * <h3>★ DB 가 없으면 건너뛴다</h3>
 * {@code LORE_TEST_DB_*} 가 있어야 컨텍스트가 뜬다 — {@link ZzalIntegrationTest} 와 같은 조건이다.
 */
@ZzalIntegrationTest
@DisplayName("API 문서 — 인증 표시가 실제 권한 규칙과 맞는다")
class OpenApiSecurityIT {

    /** 문서 안에서 쿠키 인증을 부르는 이름. */
    private static final String SCHEME = "cookieAuth";

    /** 인증 쿠키 이름. {@code JwtAuthenticationFilter.ACCESS_COOKIE} 와 같아야 한다. */
    private static final String COOKIE = "access_token";

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Value("${springdoc.api-docs.path}")
    private String apiDocsPath;

    // ── 1. 로그인 사용자 번호는 문서에 없어야 한다 ──────────────────────────

    @Test
    @DisplayName("★ 어떤 API 에도 userId 파라미터가 없다 — 쿠키에서 꺼내는 값이라 요청에 붙이는 게 아니다")
    void noOperationAsksForUserId() throws Exception {
        List<String> offenders = new ArrayList<>();
        eachOperation((path, method, operation) -> {
            for (JsonNode parameter : operation.path("parameters")) {
                if ("userId".equals(parameter.path("name").asText())) {
                    offenders.add("%s %s (in=%s)".formatted(method, path, parameter.path("in").asText()));
                }
            }
        });

        assertThat(offenders)
                .as("""
                        userId 가 문서에 남아 있습니다. 이 값은 @LoginUser 가 쿠키에서 꺼내 넣어 주는 것이라
                        요청에 붙이는 값이 아닌데, 문서에 보이면 읽는 사람이 ?userId=7 을 붙여 불러야 하는 줄 압니다.
                        (ApiDocsConfig 의 addAnnotationsToIgnore 가 빠졌을 때 이렇게 됩니다)""")
                .isEmpty();
    }

    // ── 2. 쿠키 인증이 선언돼 있어야 한다 ────────────────────────────────

    @Test
    @DisplayName("★ components.securitySchemes 에 access_token 쿠키 방식이 있다")
    void theCookieSchemeIsDeclared() throws Exception {
        JsonNode scheme = apiDocs().path("components").path("securitySchemes").path(SCHEME);

        assertThat(scheme.isMissingNode())
                .as("인증 방식이 하나도 선언돼 있지 않으면 스웨거에 자물쇠가 안 뜹니다")
                .isFalse();
        assertThat(scheme.path("type").asText()).isEqualTo("apiKey");
        assertThat(scheme.path("in").asText()).isEqualTo("cookie");
        assertThat(scheme.path("name").asText())
                .as("JwtAuthenticationFilter 가 읽는 쿠키 이름과 같아야 합니다")
                .isEqualTo(COOKIE);
    }

    @Test
    @DisplayName("자물쇠 모양은 한 가지뿐 — 있으면 [cookieAuth], 없으면 아예 없다")
    void securityIsEitherTheCookieSchemeOrAbsent() throws Exception {
        List<String> odd = new ArrayList<>();
        eachOperation((path, method, operation) -> {
            JsonNode security = operation.path("security");
            if (security.isMissingNode()) {
                return;
            }
            boolean expected = security.isArray() && security.size() == 1
                    && security.get(0).has(SCHEME)
                    && security.get(0).path(SCHEME).isArray()
                    && security.get(0).path(SCHEME).isEmpty();
            if (!expected) {
                odd.add("%s %s -> %s".formatted(method, path, security));
            }
        });

        assertThat(odd).as("문서에 우리가 안 만든 인증 표시가 섞였습니다").isEmpty();
    }

    // ── 3. 붙은 자리 / 안 붙은 자리가 실제 규칙과 같아야 한다 ────────────

    @Test
    @DisplayName("★★ 자물쇠가 붙은 곳과 안 붙은 곳이 WebSecurityConfig 의 규칙과 한 건도 안 어긋난다")
    void theLockMatchesTheRealAccessRules() throws Exception {
        List<String> shouldBeLocked = new ArrayList<>();
        List<String> shouldBeOpen = new ArrayList<>();
        int[] counted = {0, 0};

        eachOperation((path, method, operation) -> {
            boolean locked = !operation.path("security").isMissingNode();
            boolean mustLock = shouldRequireLogin(path, method);
            if (mustLock) {
                counted[0]++;
            } else {
                counted[1]++;
            }
            if (mustLock && !locked) {
                shouldBeLocked.add(method + " " + path);
            }
            if (!mustLock && locked) {
                shouldBeOpen.add(method + " " + path);
            }
        });

        assertThat(shouldBeLocked)
                .as("로그인이 필요한데 문서에는 자물쇠가 없습니다 — 로그인 없이 되는 API 로 읽힙니다")
                .isEmpty();
        assertThat(shouldBeOpen)
                .as("""
                        로그인 없이 되는데 문서에 자물쇠가 붙었습니다. 이쪽이 더 나쁩니다 —
                        랜딩·회원가입이 "로그인해야 한다" 로 읽히면 화면이 굳이 막아 버립니다""")
                .isEmpty();

        // 양쪽이 다 0 이면 "아무 API 도 못 찾았다" 는 상태에서도 초록이 된다. 그걸 막는다.
        assertThat(counted[0]).as("자물쇠가 붙어야 할 API 를 하나도 못 찾았습니다").isGreaterThan(0);
        assertThat(counted[1]).as("열려 있어야 할 API 를 하나도 못 찾았습니다").isGreaterThan(0);
    }

    @Test
    @DisplayName("눈으로 고른 대표 주소 — 열린 것엔 없고, 로그인 뒤의 것엔 있다")
    void spotChecks() throws Exception {
        Map<String, JsonNode> operations = operationsByKey();

        // 로그인 없이 되어야 하는 것
        for (String open : List.of(
                "POST /api/v1/auth/signup",
                "POST /api/v1/auth/login",
                "POST /api/v1/auth/refresh",
                "POST /api/v1/events",
                "GET /api/zzal/v1/public/share/{token}",
                "GET /api/webtoon/v1/config",
                "POST /api/webtoon/v1/nh/create")) {
            assertThat(operations).containsKey(open);
            assertThat(operations.get(open).path("security").isMissingNode())
                    .as(open + " 는 로그인 없이 되는 주소입니다")
                    .isTrue();
        }

        // 로그인 뒤에 있는 것
        for (String locked : List.of(
                "GET /api/v1/users/me",
                "GET /api/v1/credits/me",
                "POST /api/v1/uploads/presign",
                "GET /api/zzal/v1/me/pets",
                "POST /api/zzal/v1/me/pets/{petId}/care",
                "GET /api/webtoon/v1/my/runs")) {
            assertThat(operations).containsKey(locked);
            assertThat(operations.get(locked).path("security").path(0).has(SCHEME))
                    .as(locked + " 는 로그인이 필요한 주소입니다")
                    .isTrue();
        }
    }

    // ── 기대값 — WebSecurityConfig 를 보고 옮겨 적은 것 ────────────────────

    /**
     * 이 주소·방식이 로그인을 요구하는가.
     *
     * <p>★ {@code WebSecurityConfig.filterChain} 의 {@code authorizeHttpRequests} 를 위에서부터
     * 그대로 옮긴 것이다. <b>먼저 걸리는 줄이 이긴다</b> — 웹툰이 그 예로, 좁은 {@code my/**} 가
     * 넓은 {@code /api/webtoon/**} 보다 앞에 있어야 한다.
     *
     * <p>문서 생성 쪽 코드를 부르지 않는 것이 요점이다. 갖다 쓰면 그쪽이 틀렸을 때 같이 틀린다.
     */
    private static boolean shouldRequireLogin(String path, String method) {
        if (path.startsWith("/api/v1/auth/")) {
            return false;                                   // 가입·로그인·재발급·로그아웃
        }
        if (path.equals("/actuator/health")
                || path.startsWith("/api/swagger-ui")
                || path.startsWith("/api/v3/api-docs")
                || path.startsWith("/swagger-ui")
                || path.startsWith("/v3/api-docs")) {
            return false;                                   // 상태 확인·문서 자신
        }
        if (method.equals("GET") && path.startsWith("/api/zzal/v1/public/")) {
            return false;                                   // 랜딩·공유 보기(조회만)
        }
        if (method.equals("GET") && path.startsWith("/api/trailer/v1/public/")) {
            return false;                                   // 복선 카드 보기
        }
        if (path.startsWith("/api/zzal/v1/agent/")) {
            return false;                                   // 전용 열쇠(X-Zzal-Agent-Key)로 지키는 문
        }
        if (path.startsWith("/api/webtoon/v1/my/")) {
            return true;                                    // ★ 넓은 웹툰 규칙보다 먼저
        }
        if (path.startsWith("/api/webtoon/")) {
            return false;                                   // 스튜디오는 로그인 없이 끝까지 만든다
        }
        if (method.equals("POST") && path.equals("/api/v1/events")) {
            return false;                                   // 비로그인 행동 기록
        }
        if (path.equals("/api/test/email")) {
            return false;                                   // SMTP 시험용 임시 주소
        }
        return true;                                        // 나머지는 로그인 필요
    }

    // ── 문서 훑기 ─────────────────────────────────────────────────────────

    @FunctionalInterface
    private interface OperationVisitor {
        void visit(String path, String method, JsonNode operation);
    }

    /** OpenAPI 의 HTTP 방식만 고른다 — {@code parameters}·{@code summary} 같은 형제 키는 건너뛴다. */
    private static final List<String> METHODS =
            List.of("get", "post", "put", "patch", "delete", "head", "options", "trace");

    private void eachOperation(OperationVisitor visitor) throws Exception {
        JsonNode paths = apiDocs().path("paths");
        for (String path : new TreeSet<>(iterate(paths))) {
            JsonNode item = paths.get(path);
            for (String method : METHODS) {
                if (item.has(method)) {
                    visitor.visit(path, method.toUpperCase(Locale.ROOT), item.get(method));
                }
            }
        }
    }

    private Map<String, JsonNode> operationsByKey() throws Exception {
        Map<String, JsonNode> out = new java.util.LinkedHashMap<>();
        eachOperation((path, method, operation) -> out.put(method + " " + path, operation));
        return out;
    }

    private static List<String> iterate(JsonNode object) {
        List<String> names = new ArrayList<>();
        object.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private JsonNode apiDocs() throws Exception {
        var response = mockMvc.perform(get(apiDocsPath)).andReturn().getResponse();
        if (response.getStatus() != 200) {
            throw new IllegalStateException(
                    "%s 가 %d 을 냈습니다. springdoc 이 꺼져 있거나(SPRINGDOC_ENABLED) 주소가 바뀌었습니다."
                            .formatted(apiDocsPath, response.getStatus()));
        }
        return JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8));
    }
}
