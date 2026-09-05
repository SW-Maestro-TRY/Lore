package com.lore.webtoon;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 웹툰 생성 하네스가 어디에 떠 있는가.
 *
 * 기본값은 로컬에서 {@code haeun/landing/serve.py} 를 띄웠을 때의 주소다.
 * 그래서 <b>application.yml 을 안 고쳐도</b> 바로 뜬다 — 그 파일은 공용
 * 자리(apps/api)라 도메인 하나 때문에 건드리지 않는다. 배포에서 주소가
 * 다르면 환경변수 {@code LORE_WEBTOON_HARNESS_BASEURL} 로 덮는다.
 *
 * <h2>왜 프록시인가</h2>
 *
 * 생성 파이프라인은 파이썬이다({@code haeun/new_harness}). 그 앞에
 * {@code serve.py} 가 이미 서서 줄 세우기 · 검수 진행 표시 · 판본 ·
 * 오버레이 · 굽기 · 워터마크를 다 하고 있고, 그것들은 실제 한 편을 만들어
 * 보며 검증된 코드다. 같은 것을 자바로 다시 쓰면 검증을 처음부터 다시
 * 해야 한다.
 *
 * 그래서 지금 이 도메인의 백엔드가 하는 일은 <b>앞에 서 주는 것</b>이다.
 * 인증 · 크레딧 · DB 처럼 자바가 맡아야 할 것이 생기면 그때 이 자리에서
 * 하나씩 가로채면 된다 — 프론트가 부르는 주소는 안 바뀐다.
 */
@ConfigurationProperties(prefix = "lore.webtoon.harness")
public class HarnessProperties {

    /** serve.py 가 떠 있는 곳. */
    private String baseUrl = "http://127.0.0.1:8800";

    /**
     * 한 호출을 얼마나 기다리는가.
     *
     * 웹툰 한 편은 몇 분이 걸리지만 <b>한 호출이 그만큼 걸리지는 않는다</b> —
     * 프론트가 만들기를 시켜 놓고 상태를 폴링하는 구조라 호출 하나하나는
     * 짧다. 가장 오래 걸리는 것은 이미지로 굽기(한 편을 이어 붙여 내려보냄)
     * 라서 그 정도만 잡는다.
     */
    private Duration timeout = Duration.ofSeconds(120);

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }
}
