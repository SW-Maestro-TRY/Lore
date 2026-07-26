package com.lore.common.auth;

/**
 * 회원가입 / 로그인 API 자리.
 *
 * 계정은 도메인별로 나누지 않고 공통으로 하나만 쓰기로 했으므로 common 에 둔다.
 * (회의에서 정한 "공통은 DB / 회원가입 / 설정" 원칙)
 *
 * spring-web 의존성이 아직 없어서 애노테이션은 주석으로만 남겨둠.
 *
 * <pre>
 * &#64;RestController
 * &#64;RequestMapping("/api/auth")
 * public class AuthController {
 *     &#64;PostMapping("/signup") ...
 *     &#64;PostMapping("/login")  ...
 * }
 * </pre>
 */
public class AuthController {
}
