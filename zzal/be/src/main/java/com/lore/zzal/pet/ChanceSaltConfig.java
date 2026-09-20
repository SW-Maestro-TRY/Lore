package com.lore.zzal.pet;

import com.lore.common.auth.jwt.JwtProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 부팅 때 {@link Chance} 에 서버 비밀을 건다.
 *
 * <h3>★ 왜 새 설정 키를 안 만드나</h3>
 * 키를 하나 더 만들면 dev·운영 양쪽에 배관(환경변수·Parameter Store)을 새로 깔아야 하고, 한쪽을 빠뜨리면
 * <b>부팅이 막히거나 조용히 빈 값으로 돈다.</b> 이미 모든 환경에 반드시 있는 값(JWT 서명 키)에서 파생하면
 * 그 위험이 없다 — 그 키가 없으면 애초에 로그인이 안 되므로 "있는지" 를 따로 걱정할 필요가 없다.
 *
 * ★ 비밀 자체가 아니라 <b>해시로 한 번 접은 값</b>을 넘긴다({@link Chance#useServerSecret}).
 *
 * ★ 파일 이름에 "secret" 을 안 쓴 이유 — 레포의 커밋 훅이 <b>파일명에 그 낱말이 들어가면 커밋을 막는다</b>
 *   (키 파일이 실수로 올라가는 것을 막는 장치다). 이 파일에는 비밀이 없지만 이름만으로 걸리므로 피해서 짓는다.
 */
@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class ChanceSaltConfig {

    public ChanceSaltConfig(JwtProperties jwt) {
        Chance.useServerSecret(jwt.secret());
    }
}
