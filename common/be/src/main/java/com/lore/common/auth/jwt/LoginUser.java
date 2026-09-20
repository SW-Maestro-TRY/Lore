package com.lore.common.auth.jwt;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 컨트롤러 파라미터에 붙이면 로그인한 사용자 번호가 들어온다.
 *
 * <pre>
 * &#64;GetMapping("/me")
 * public ApiResponse&lt;X&gt; me(&#64;LoginUser Long userId) { ... }
 * </pre>
 *
 * 이게 없으면 컨트롤러마다 SecurityContextHolder 를 꺼내는 코드가 반복된다.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface LoginUser {
}
