package com.lore.zzal.generation.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;

/**
 * OpenAI 기본 주소 한 곳. 설정 {@code app.zzal.openai.base-url}(env {@code ZZAL_OPENAI_BASE_URL})이 비면 실제 주소.
 * 그림·문단·대사 클라이언트가 모두 여기서 주소를 만든다. 벤치마크 흉내 서버(과금 0)를 dev에서만 가리키기 위한 것.
 * 운영({@code LORE_ENV=prod})에서 기본 주소가 아니면 기동을 막는다.
 */
@Component
public class OpenAiBaseUrl {
    public static final String DEFAULT = "https://api.openai.com";

    public OpenAiBaseUrl(@Value("${app.zzal.openai.base-url:}") String configured,
                         @Value("${app.lore.env:}") String loreEnv) {
        guard(configured, loreEnv);
    }

    /** 비었으면 기본 주소, 끝의 '/'는 뗀다. */
    public static String resolve(String configured) {
        if (configured == null || configured.isBlank()) return DEFAULT;
        String s = configured.strip();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    public static URI endpoint(String configured, String path) {
        return URI.create(resolve(configured) + path);
    }

    /** 운영에서 기본 주소가 아니면 실패. */
    public static void guard(String configured, String loreEnv) {
        if (loreEnv != null && "prod".equalsIgnoreCase(loreEnv.strip()) && !DEFAULT.equals(resolve(configured)))
            throw new IllegalStateException("LORE_ENV=prod 에서는 app.zzal.openai.base-url 을 바꿀 수 없습니다(벤치마크 흉내 주소는 dev 전용)");
    }
}
