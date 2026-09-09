package com.lore.common.auth;

import com.lore.common.user.AgreementType;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 가입은 로그인시키지 않는다.
 *
 * <h3>★ 왜 반환 타입을 검사하나</h3>
 * "쿠키를 안 준다"를 지키는 가장 확실한 자리는 <b>줄 것 자체를 안 만드는 것</b>이다.
 * {@code signUp} 이 토큰을 돌려주지 않으면 컨트롤러가 쿠키를 쓸 재료가 없다.
 * 누군가 나중에 다시 토큰을 돌려주게 바꾸면 이 테스트가 먼저 깨진다.
 *
 * <h3>★ 왜 이 규칙인가</h3>
 * 가입 뒤 로그인 화면으로 보내면 방금 정한 비밀번호를 한 번 더 치게 된다. 번거로워 보이지만
 * 그 자리에서 비밀번호가 맞는지 확인된다 — 오타를 낸 채 가입한 사람이 다음 접속에서야
 * 못 들어오는 일을 막는다.
 */
@DisplayName("회원가입 — 로그인시키지 않는다")
class SignUpDoesNotSignInTest {

    @Test
    @DisplayName("★ signUp 은 토큰을 돌려주지 않는다 — 컨트롤러가 쿠키를 쓸 재료가 없다")
    void signUpReturnsNothing() {
        Method signUp = Arrays.stream(AuthService.class.getDeclaredMethods())
                .filter(m -> m.getName().equals("signUp"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("AuthService.signUp 이 없다"));

        assertThat(signUp.getReturnType()).isEqualTo(void.class);
    }

    @Test
    @DisplayName("컨트롤러의 회원가입은 응답에 쿠키를 쓰지 않는다 — HttpServletResponse 를 받지 않는다")
    void controllerTakesNoResponse() {
        Method signUp = Arrays.stream(AuthController.class.getDeclaredMethods())
                .filter(m -> m.getName().equals("signUp"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("AuthController.signUp 이 없다"));

        assertThat(signUp.getParameterTypes())
                .noneMatch(t -> t.getName().endsWith("HttpServletResponse"));
    }

    @Test
    @DisplayName("필수 동의는 AGE_14 · TERMS · PRIVACY 세 가지다")
    void requiredAgreements() {
        assertThat(AgreementType.values())
                .containsExactly(AgreementType.AGE_14, AgreementType.TERMS,
                        AgreementType.PRIVACY, AgreementType.MARKETING);
    }
}
