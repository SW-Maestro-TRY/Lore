package com.lore.zzal.openai;

import java.net.http.HttpClient;

/**
 * 시험용 창구 만들기. 운영 코드는 스프링 창구 빈만 쓰고, 넘겨받은 HttpClient 로 보내는 창구
 * ({@link OpenAiGateway#standalone})는 같은 패키지 전용이다(P1 검토 표 3번). 다른 패키지의 시험은 여기를 거친다.
 */
public final class TestGateways {

    private TestGateways() {
    }

    /** 흉내·목 HttpClient 하나로 모든 줄을 보내는 창구(줄 상한 기본값). */
    public static OpenAiGateway over(HttpClient http) {
        return OpenAiGateway.standalone(http);
    }

    /** 운영과 같은 모양(줄마다 HttpClient, 기본 상한)의 창구. */
    public static OpenAiGateway lanes() {
        return new OpenAiGateway(LaneConfig.defaults());
    }
}
