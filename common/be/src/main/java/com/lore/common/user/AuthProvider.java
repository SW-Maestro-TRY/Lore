package com.lore.common.user;

/**
 * 로그인 수단.
 *
 * 지금은 LOCAL 만 쓴다. 구글·카카오는 여기에 값을 추가하고 credential 행을 하나 더 만들면 되며,
 * 계정 표(users)는 건드리지 않는다.
 */
public enum AuthProvider {

    /** 이메일 + 비밀번호 */
    LOCAL,

    GOOGLE,

    KAKAO
}
