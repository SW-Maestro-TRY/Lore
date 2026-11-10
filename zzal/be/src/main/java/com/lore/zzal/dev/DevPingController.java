package com.lore.zzal.dev;

import com.lore.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 리모컨 노출 판정(결정 3 "서버 ping") — 화면은 이 주소가 200 일 때만 리모컨을 그린다.
 *
 * ★ 도메인(hostname)으로 가르지 않는 이유 — dev.lorecomic.com 은 운영과 같은 빌드라 도메인으로는 dev 를 못 가른다.
 *   진짜 자물쇠는 서버의 스위치({@code app.zzal.dev-tools})다. 꺼진 서버에는 이 주소가 없다(404) → 리모컨도 안 뜬다.
 */
@Tag(name = "개발용", description = "시연·확인 전용. 운영에서는 꺼져 있어 존재하지 않는다")
@RestController
@ConditionalOnProperty(name = "app.zzal.dev-tools", havingValue = "true")
public class DevPingController {

    private final boolean generationReal;

    public DevPingController(@Value("${app.zzal.generation.real:false}") boolean generationReal) {
        this.generationReal = generationReal;
    }

    @Operation(summary = "개발 도구 켜짐 확인", description = "200 이면 리모컨을 그린다. 꺼진 서버에는 이 주소가 없다(404). 진짜 생성 스위치도 함께 알린다.")
    @GetMapping("/api/zzal/v1/dev/ping")
    public ApiResponse<DevResponses.DevPing> devPing() {
        return ApiResponse.ok(new DevResponses.DevPing(true, generationReal));
    }
}
