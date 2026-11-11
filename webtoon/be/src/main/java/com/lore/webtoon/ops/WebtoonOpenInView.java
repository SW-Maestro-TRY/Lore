package com.lore.webtoon.ops;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * open-in-view 를 <b>웹툰 API 에서만</b> 끈다(#1028).
 *
 * <h2>왜</h2>
 * open-in-view 가 켜져 있으면 DB 를 한 번이라도 쓴 요청은 응답이 끝날 때까지 연결을 쥐고 있다. 그림을
 * 주고받거나 바깥 서비스를 부르는 요청이 오래 걸리면 그동안 연결이 묶이고, 그런 요청이 열 개 겹치면 풀(10개)이
 * 바닥나 아무 요청이나 30초 기다리다 실패한다. 2026-11-11 20:06(KST) 운영에서 실제로 11건이 한꺼번에 그렇게
 * 실패했다. 그 시각 DB 자체는 한가했다(CPU 4%, 지연 1~3ms).
 *
 * <h2>어떻게</h2>
 * {@code application.yml} 의 {@code spring.jpa.open-in-view=false} 로 스프링이 자동으로 거는 인터셉터를
 * 빼고, 여기서 같은 인터셉터를 <b>웹툰 주소를 뺀 나머지</b>에 다시 건다. 그래서 짤·공통 등 다른 도메인은
 * 예전과 똑같이 동작한다 — 그쪽에는 지연 로딩 관계(UserCredential 등)가 있어 끄면 깨질 수 있다.
 *
 * 웹툰 엔티티에는 지연 로딩 관계가 하나도 없다(@ManyToOne 등 없음). 그래서 끄더라도 트랜잭션 밖에서 엔티티를
 * 읽다 깨지는 일이 없다. 저장소 호출은 그때그때 연결을 빌렸다 바로 돌려준다.
 *
 * {@code EntityManagerFactory} 는 {@code ObjectProvider} 로 받는다 — 화면 단위 검사({@code @WebMvcTest})는
 * 이 설정을 끌어오지만 JPA 는 안 띄워서, 바로 받으면 웹툰 화면 검사가 전부 문맥을 못 띄운다.
 */
@Configuration
public class WebtoonOpenInView implements WebMvcConfigurer {

    private final ObjectProvider<EntityManagerFactory> emfs;

    public WebtoonOpenInView(ObjectProvider<EntityManagerFactory> emfs) {
        this.emfs = emfs;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        EntityManagerFactory emf = emfs.getIfAvailable();
        if (emf == null) {
            return;                                 // JPA 없이 뜬 검사 문맥
        }
        OpenEntityManagerInViewInterceptor osiv = new OpenEntityManagerInViewInterceptor();
        osiv.setEntityManagerFactory(emf);
        registry.addWebRequestInterceptor(osiv).excludePathPatterns("/api/webtoon/**");
    }
}
