package com.lore.docs;

import com.lore.common.auth.jwt.JwtAuthenticationFilter;
import com.lore.common.auth.jwt.LoginUser;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.util.AntPathMatcher;

import java.util.List;

/**
 * API 문서가 <b>사실과 다르게</b> 보이던 두 가지를 바로잡는다.
 *
 * <h3>왜 여기(apps/api)에 있나</h3>
 * 제목·설명은 이미 {@code common} 의 설정이 정하고 있다. 그 파일은 세 도메인이 함께 쓰는 자리라
 * 한 도메인 사정으로 고치지 않는다. springdoc 은 <b>만들어진 문서를 나중에 손보는 자리</b>
 * ({@link GlobalOpenApiCustomizer})를 따로 열어 두므로, 공용 파일을 건드리지 않고 여기서 덧붙인다.
 *
 * <h3>1. 로그인 사용자 번호가 "필수 쿼리 파라미터" 로 보이던 것</h3>
 * {@link LoginUser} 가 붙은 자리는 요청에서 받는 값이 아니다 — 쿠키에 담긴 토큰을 서버가 풀어
 * 넣어 주는 값이다(LoginUserArgumentResolver). 그런데 springdoc 은 그 사정을 모르고 평범한
 * {@code Long} 파라미터로 읽어, 거의 모든 조회 API 에 {@code userId} 를 <b>required</b> 로 그렸다.
 * 문서를 읽는 사람은 {@code ?userId=7} 을 붙여 불러야 하는 줄 알게 되고, 실제로 붙이면 그 값은
 * 조용히 무시된다 — 틀린 줄도 모른 채 남의 번호를 넣어 보는 일까지 생긴다.
 *
 * <p>그래서 <b>문서를 만드는 단계에서</b> 이 어노테이션이 붙은 파라미터를 아예 빼게 한다
 * ({@code addAnnotationsToIgnore}). 다 만든 뒤 이름({@code userId})으로 지우는 방법도 있지만,
 * 그건 같은 이름의 진짜 파라미터가 언젠가 생기면 그것까지 지운다 — 지워진 줄도 모르게.
 *
 * <h3>2. 자물쇠가 하나도 없던 것</h3>
 * 이 서비스는 {@code access_token} 쿠키로 인증한다. 그런데 문서에 인증 방식이 한 줄도 없어서
 * 스웨거에 자물쇠가 안 뜨고, "로그인 없이 되는 API" 처럼 보였다.
 * 쿠키 방식을 선언하고, <b>로그인이 필요한 주소에만</b> 자물쇠를 붙인다.
 *
 * <p>★ 열린 주소까지 자물쇠가 붙으면 이번엔 반대로 거짓말이 된다. 아래 {@link #needsLogin}
 * 는 실제 권한 규칙({@code WebSecurityConfig} 의 {@code authorizeHttpRequests})과
 * <b>같은 순서로</b> 같은 판정을 한다. 두 곳이 어긋나면 문서만 틀리고 서버는 멀쩡해서
 * 아무도 모르므로, 시험({@code OpenApiSecurityIT})이 판정 결과를 직접 붙잡아 둔다.
 */
@Configuration
public class ApiDocsConfig {

    /** 문서 안에서 이 인증 방식을 부르는 이름. 스웨거 "Authorize" 창에 그대로 뜬다. */
    public static final String COOKIE_SCHEME = "cookieAuth";

    static {
        // ★ 정적 블록인 이유 — springdoc 은 첫 문서 요청 때 컨트롤러를 훑는데, 그때 이 목록을 본다.
        //   설정 빈이 만들어지는 시점(기동)이 그보다 한참 앞이라 늦을 일이 없다.
        SpringDocUtils.getConfig().addAnnotationsToIgnore(LoginUser.class);
    }

    private static final AntPathMatcher PATHS = new AntPathMatcher();

    @Bean
    public GlobalOpenApiCustomizer loreAuthDocsCustomizer() {
        return openApi -> {
            declareCookieScheme(openApi);
            lockProtectedOperations(openApi);
        };
    }

    /** 인증 방식 선언 — 쿠키 하나에 토큰이 들어 있다는 사실만 적는다(값은 적지 않는다). */
    private void declareCookieScheme(OpenAPI openApi) {
        if (openApi.getComponents() == null) {
            openApi.setComponents(new Components());
        }
        openApi.getComponents().addSecuritySchemes(COOKIE_SCHEME, new SecurityScheme()
                .type(SecurityScheme.Type.APIKEY)
                .in(SecurityScheme.In.COOKIE)
                .name(JwtAuthenticationFilter.ACCESS_COOKIE)
                .description("""
                        로그인하면 서버가 내려주는 HttpOnly 쿠키 `access_token`. \
                        브라우저가 알아서 실어 보내므로 화면에서 따로 붙일 것이 없다. \
                        (앱처럼 쿠키를 쓰기 어려운 곳은 `Authorization: Bearer <토큰>` 도 받는다.)"""));
    }

    /** 로그인이 필요한 오퍼레이션에만 자물쇠를 건다. */
    private void lockProtectedOperations(OpenAPI openApi) {
        if (openApi.getPaths() == null) {
            return;
        }
        List<SecurityRequirement> cookie = List.of(new SecurityRequirement().addList(COOKIE_SCHEME));
        openApi.getPaths().forEach((path, item) -> eachOperation(item, path, cookie));
    }

    private void eachOperation(PathItem item, String path, List<SecurityRequirement> cookie) {
        item.readOperationsMap().forEach((method, operation) -> {
            if (needsLogin(path, method.name())) {
                // ★ add 가 아니라 set — 문서를 두 번 만들어도 자물쇠가 두 개로 늘지 않는다.
                operation.setSecurity(cookie);
            }
        });
    }

    /**
     * 이 주소·방식이 로그인을 요구하는가.
     *
     * <p>★ 순서가 곧 규칙이다 — 먼저 걸리는 줄이 이긴다. {@code WebSecurityConfig} 와 같은 순서로
     * 적혀 있고, 순서를 바꾸면 판정이 달라진다(웹툰이 그 예 — 좁은 {@code my/**} 가 먼저다).
     */
    static boolean needsLogin(String path, String httpMethod) {
        // 로그인 없이 열려 있어야 하는 것
        if (matches(path, "/api/v1/auth/**")) {
            return false;
        }
        if (matches(path, "/actuator/health")
                || matches(path, "/api/swagger-ui/**")
                || matches(path, "/api/swagger-ui.html")
                || matches(path, "/api/v3/api-docs/**")
                || matches(path, "/swagger-ui/**")
                || matches(path, "/swagger-ui.html")
                || matches(path, "/v3/api-docs/**")) {
            return false;
        }
        // 조회만 열어 두는 것 — 랜딩·공개 목록. GET 이 아니면 아래로 흘러 로그인이 필요해진다.
        if (HttpMethod.GET.name().equals(httpMethod) && matches(path, "/api/zzal/v1/public/**")) {
            return false;
        }
        // 맥미니(codex 러너) 전용 문 — 사람 로그인이 아니라 전용 열쇠(X-Zzal-Agent-Key)로 지킨다.
        // 쿠키 자물쇠를 그리면 "로그인하면 부를 수 있다" 는 거짓말이 된다.
        if (matches(path, "/api/zzal/v1/agent/**")) {
            return false;
        }
        // ★ 넓은 웹툰 규칙보다 먼저 — 내 작업물만 로그인 뒤에 있다.
        if (matches(path, "/api/webtoon/v1/my/**")) {
            return true;
        }
        if (matches(path, "/api/webtoon/**")) {
            return false;
        }
        // 행동 기록은 로그인 전에도 받는다 — 가장 알고 싶은 것이 "가입 안 하고 나간 사람" 이라서다.
        if (HttpMethod.POST.name().equals(httpMethod) && matches(path, "/api/v1/events")) {
            return false;
        }
        // 나머지는 로그인 필요.
        return true;
    }

    private static boolean matches(String path, String pattern) {
        return PATHS.match(pattern, path);
    }
}
