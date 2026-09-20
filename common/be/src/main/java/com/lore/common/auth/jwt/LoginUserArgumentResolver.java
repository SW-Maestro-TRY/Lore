package com.lore.common.auth.jwt;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** {@link LoginUser} 가 붙은 파라미터에 로그인 사용자 번호를 넣어 준다. */
@Component
public class LoginUserArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(LoginUser.class)
                && Long.class.isAssignableFrom(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mav,
                                  NativeWebRequest request, WebDataBinderFactory binder) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Long userId)) {
            // 경로 규칙에서 이미 걸러졌어야 하는 상황이다. 여기까지 왔다면 설정이 어긋난 것이므로
            // 조용히 null 을 넘기지 않고 분명히 실패시킨다.
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return userId;
    }
}
